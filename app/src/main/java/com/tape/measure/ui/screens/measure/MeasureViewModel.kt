package com.tape.measure.ui.screens.measure

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.tape.measure.data.db.MeasurementEntity
import com.tape.measure.data.prefs.UserPreferencesRepository
import com.tape.measure.data.repository.MeasurementRepository
import com.tape.measure.domain.accuracy.AccuracyEngine
import com.tape.measure.domain.accuracy.AccuracyInput
import com.tape.measure.domain.accuracy.AnchorObservation
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.model.AccuracyEstimate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * ViewModel for the AR measurement screen.
 *
 * Threading model
 * ───────────────
 * • [onFrame] is called from SceneView's GL render thread (60 fps).
 *   We do the 3-D→2-D projection there and write to [_uiState] directly —
 *   StateFlow.update() is internally thread-safe via CAS, so no dispatch needed.
 *   We extract only primitive FloatArrays from ARCore objects (no ARCore handles
 *   escape the render thread beyond what ARCore's own threading model allows).
 *
 * • [enqueueTap], [cycleUnit], [reset] are called from the main thread (gesture
 *   callbacks). Anchor references are @Volatile so the render thread always sees
 *   the latest value without needing a full lock.
 *
 * Accuracy
 * ────────
 * Placement metadata (how far the camera was from the surface, and whether the
 * hit landed on a tracked Plane or a raw feature point) is captured ONCE at tap
 * time — it describes the moment of placement and cannot be recovered later.
 * It is then fed to [AccuracyEngine] every frame alongside live tracking state.
 * See domain/accuracy/AccuracyEngine.kt for the error model.
 */
@HiltViewModel
class MeasureViewModel @Inject constructor(
    private val repository: MeasurementRepository,
    prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeasureUiState())
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    init {
        prefs.unitFlow
            .onEach { unit -> _uiState.update { it.copy(unitSystem = unit) } }
            .launchIn(viewModelScope)
    }

    // Anchor handles — written on main thread, read on render thread.
    @Volatile private var anchorA: Anchor? = null
    @Volatile private var anchorB: Anchor? = null

    /**
     * Placement-time metadata for each anchor. Captured at tap because
     * HitResult.distance and the trackable type are only knowable then.
     * Null until the corresponding anchor is placed.
     */
    @Volatile private var placementA: PlacementRecord? = null
    @Volatile private var placementB: PlacementRecord? = null

    // Pending tap screen coordinates — set from main thread, consumed on render thread.
    @Volatile private var pendingTapX: Float = -1f
    @Volatile private var pendingTapY: Float = -1f

    // Viewport dimensions set once from MeasureScreen's onGloballyPositioned.
    @Volatile private var viewportWidth: Int = 1
    @Volatile private var viewportHeight: Int = 1

    // Reusable float arrays to avoid per-frame allocation on the render thread.
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val worldPoint = FloatArray(4)
    private val viewPoint  = FloatArray(4)
    private val clipPoint  = FloatArray(4)

    /** What we knew about an anchor at the instant the user placed it. */
    private data class PlacementRecord(
        val rangeMeters: Float,
        val surface: AnchorSurface,
    )

    // ── Lifecycle hooks (called from MeasureScreen) ──────────────────────────

    /** Called once after ARScene is laid out; saves pixel dimensions for projection. */
    fun setViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportWidth = width
            viewportHeight = height
        }
    }

    // ── Per-frame update (render thread) ─────────────────────────────────────

    /**
     * Called every AR frame from the GL render thread. Reads tracking state,
     * processes any queued tap via [Frame.hitTest], projects anchor poses to
     * screen space, recomputes the accuracy bound, and pushes an updated
     * [MeasureUiState].
     */
    fun onFrame(camera: Camera, frame: Frame) {
        val cameraTracking = camera.trackingState == TrackingState.TRACKING
        val confidence = camera.toConfidence()

        if (!cameraTracking) {
            // Anchors may be drifting — surface that honestly rather than
            // leaving a stale tolerance on screen.
            _uiState.update {
                it.copy(
                    confidence = confidence,
                    accuracy   = if (it.phase == MeasurePhase.BOTH) {
                        AccuracyEstimate.unavailable(
                            "Camera isn't tracking. Move the phone slowly to re-lock."
                        )
                    } else {
                        null
                    },
                )
            }
            return
        }

        // Consume pending tap — hit-test against detected planes.
        val tapX = pendingTapX
        val tapY = pendingTapY
        if (tapX >= 0f) {
            pendingTapX = -1f
            pendingTapY = -1f
            frame.hitTest(tapX, tapY)
                .firstOrNull { hit ->
                    val t = hit.trackable
                    t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hit.hitPose)
                }
                ?.let { onTap(it) }
        }

        // Grab matrices — safe to call on render thread after Session.update().
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

        val trackedA = anchorA?.takeIf { it.trackingState == TrackingState.TRACKING }
        val trackedB = anchorB?.takeIf { it.trackingState == TrackingState.TRACKING }
        val poseA = trackedA?.pose
        val poseB = trackedB?.pose

        val distance = if (poseA != null && poseB != null) {
            distance(
                poseA.tx(), poseA.ty(), poseA.tz(),
                poseB.tx(), poseB.ty(), poseB.tz(),
            )
        } else {
            null
        }

        val accuracy = computeAccuracy(
            distance       = distance,
            cameraTracking = true,
            issue          = camera.trackingFailureReason.toIssue(),
            anchorATracked = trackedA != null,
            anchorBTracked = trackedB != null,
        )

        _uiState.update { state ->
            state.copy(
                confidence     = confidence,
                screenPointA   = poseA?.let { project(it.tx(), it.ty(), it.tz()) },
                screenPointB   = poseB?.let { project(it.tx(), it.ty(), it.tz()) },
                distanceMeters = distance,
                accuracy       = accuracy,
            )
        }
    }

    /**
     * Builds the accuracy estimate for the current frame, or null when there is
     * nothing to estimate yet (fewer than two points placed).
     *
     * Deliberately returns [AccuracyEstimate.unavailable] rather than null when
     * points exist but can't be trusted — "we can't verify this" is information;
     * a blank space is not.
     */
    private fun computeAccuracy(
        distance: Float?,
        cameraTracking: Boolean,
        issue: TrackingIssue,
        anchorATracked: Boolean,
        anchorBTracked: Boolean,
    ): AccuracyEstimate? {
        val recA = placementA ?: return null
        val recB = placementB ?: return null

        if (distance == null) {
            return AccuracyEstimate.unavailable(
                "A point lost tracking and may have drifted. Re-place it."
            )
        }

        return AccuracyEngine.estimate(
            AccuracyInput(
                distanceMeters   = distance,
                cameraIsTracking = cameraTracking,
                issue            = issue,
                anchorA = AnchorObservation(
                    placementRangeMeters = recA.rangeMeters,
                    surface              = recA.surface,
                    isTracking           = anchorATracked,
                ),
                anchorB = AnchorObservation(
                    placementRangeMeters = recB.rangeMeters,
                    surface              = recB.surface,
                    isTracking           = anchorBTracked,
                ),
            )
        )
    }

    // ── User-interaction handlers ─────────────────────────────────────────────

    /** Queues screen-space tap coordinates to be processed in the next [onFrame] call. */
    fun enqueueTap(x: Float, y: Float) {
        pendingTapX = x
        pendingTapY = y
    }

    /**
     * Places an anchor at the given hit result, capturing the placement metadata
     * the accuracy engine needs. Called from [onFrame] on the render thread after
     * a successful plane hit test.
     */
    fun onTap(hitResult: HitResult) {
        val record = PlacementRecord(
            // HitResult.distance is the camera→hit-point range in metres. This is
            // only available at hit time, which is why we record it now.
            rangeMeters = hitResult.distance,
            surface     = if (hitResult.trackable is Plane) {
                AnchorSurface.PLANE
            } else {
                AnchorSurface.POINT
            },
        )

        when (_uiState.value.phase) {
            MeasurePhase.IDLE -> {
                anchorA = hitResult.createAnchor()
                placementA = record
                _uiState.update { it.copy(phase = MeasurePhase.POINT_A) }
            }
            MeasurePhase.POINT_A -> {
                anchorB = hitResult.createAnchor()
                placementB = record
                _uiState.update { it.copy(phase = MeasurePhase.BOTH) }
            }
            MeasurePhase.BOTH -> reset()   // third tap = start over
        }
    }

    /** Cycles the active unit display: CM → M → IN → FT → CM. */
    fun cycleUnit() {
        _uiState.update { it.copy(unitSystem = it.unitSystem.next()) }
    }

    /** Detaches both anchors and returns the state machine to IDLE. */
    fun reset() {
        anchorA?.detach().also { anchorA = null }
        anchorB?.detach().also { anchorB = null }
        placementA = null
        placementB = null
        _uiState.update {
            it.copy(
                phase          = MeasurePhase.IDLE,
                screenPointA   = null,
                screenPointB   = null,
                distanceMeters = null,
                accuracy       = null,
                justSaved      = false,
            )
        }
    }

    /**
     * Persists the current measurement together with its real error bound.
     *
     * Refuses to save when the accuracy is unverifiable — storing a distance
     * whose error we couldn't bound would put an unqualified number in the saved
     * list, which is precisely the dishonesty this feature removes.
     */
    fun saveMeasurement(label: String = "") {
        val snapshot = _uiState.value
        val dist = snapshot.distanceMeters ?: return
        val estimate = snapshot.accuracy ?: return

        if (estimate.grade == com.tape.measure.domain.model.AccuracyGrade.UNAVAILABLE) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            repository.save(
                MeasurementEntity(
                    id              = UUID.randomUUID().toString(),
                    distanceMeters  = dist,
                    unit            = snapshot.unitSystem.name,
                    label           = label.ifBlank { null },
                    toleranceMeters = estimate.toleranceMeters,
                    accuracyGrade   = estimate.grade.name,
                    createdAt       = System.currentTimeMillis(),
                )
            )
            _uiState.update { it.copy(isSaving = false, justSaved = true) }
        }
    }

    /** Clears the transient post-save flag once the UI has acknowledged it. */
    fun consumeSavedFlag() {
        _uiState.update { it.copy(justSaved = false) }
    }

    override fun onCleared() {
        anchorA?.detach()
        anchorB?.detach()
        super.onCleared()
    }

    // ── Private math ─────────────────────────────────────────────────────────

    /**
     * Projects a world-space point (wx, wy, wz) to 2-D screen pixels.
     * Returns null when the point is behind the camera (view-space Z ≥ 0).
     *
     * NDC → Screen mapping:
     *   screenX = (ndcX + 1) / 2 * width
     *   screenY = (1 − ndcY) / 2 * height   ← Y is flipped (NDC Y=1 = top of screen)
     */
    private fun project(wx: Float, wy: Float, wz: Float): Offset? {
        worldPoint[0] = wx; worldPoint[1] = wy; worldPoint[2] = wz; worldPoint[3] = 1f

        Matrix.multiplyMV(viewPoint, 0, viewMatrix, 0, worldPoint, 0)
        if (viewPoint[2] >= 0f) return null  // behind camera

        Matrix.multiplyMV(clipPoint, 0, projMatrix, 0, viewPoint, 0)
        val w = clipPoint[3]
        if (w == 0f) return null

        return Offset(
            x = (clipPoint[0] / w + 1f) / 2f * viewportWidth,
            y = (1f - clipPoint[1] / w) / 2f * viewportHeight,
        )
    }

    private fun distance(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
    ): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Session-level tracking quality for the HUD badge.
     *
     * A camera that is TRACKING but reporting a failure reason is recovering
     * rather than locked, so it is reported as LOW — not HIGH.
     */
    private fun Camera.toConfidence(): TrackingConfidence = when (trackingState) {
        TrackingState.TRACKING ->
            if (trackingFailureReason == TrackingFailureReason.NONE) {
                TrackingConfidence.HIGH
            } else {
                TrackingConfidence.LOW
            }
        TrackingState.PAUSED  -> TrackingConfidence.LOW
        TrackingState.STOPPED -> TrackingConfidence.NOT_TRACKING
    }

    /** Bridges ARCore's native-backed enum to the engine's pure-Kotlin mirror. */
    private fun TrackingFailureReason.toIssue(): TrackingIssue = when (this) {
        TrackingFailureReason.NONE                  -> TrackingIssue.NONE
        TrackingFailureReason.BAD_STATE             -> TrackingIssue.BAD_STATE
        TrackingFailureReason.INSUFFICIENT_LIGHT    -> TrackingIssue.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION      -> TrackingIssue.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingIssue.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE    -> TrackingIssue.CAMERA_UNAVAILABLE
    }
}

package com.tape.measure.ui.screens.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tape.measure.data.db.MeasurementEntity
import com.tape.measure.data.db.ReadingsJson
import com.tape.measure.data.prefs.UserPreferencesRepository
import com.tape.measure.data.repository.MeasurementRepository
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.shape.MeasureSession
import com.tape.measure.domain.shape.MeasureTool
import com.tape.measure.domain.shape.Quantity
import com.tape.measure.domain.shape.SessionState
import com.tape.measure.domain.shape.Stage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

/**
 * ViewModel for the measurement screen.
 *
 * The measuring itself lives in [MeasureSession]; this class feeds it camera
 * frames and user actions, and turns what comes back into screen coordinates.
 *
 * Threading
 * ─────────
 * [onFrame] is called once per camera frame — from SceneView's GL render
 * thread on a phone, from the main thread in the practice room. The session is
 * only ever touched inside [onFrame]. User actions arrive on the main thread
 * and are queued, then applied at the start of the next frame, so nothing
 * needs a lock. Writing to [_uiState] from the render thread is safe:
 * StateFlow.update() is a compare-and-set.
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

    private sealed interface Command {
        data object Tap : Command
        data object Undo : Command
        data object Finish : Command
        data object Reset : Command
        data class Select(val tool: MeasureTool) : Command
    }

    private val session = MeasureSession()
    private val commands = ConcurrentLinkedQueue<Command>()

    // Pixel size of the camera view, for projecting the overlay.
    @Volatile private var viewportWidth: Int = 1
    @Volatile private var viewportHeight: Int = 1

    fun setViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportWidth = width
            viewportHeight = height
        }
    }

    // ── Per-frame update ─────────────────────────────────────────────────────

    /** Applies queued actions, advances the session, and publishes the result. */
    fun onFrame(frame: ArFrame) {
        var tapped = false
        var changed = false
        while (true) {
            val command = commands.poll() ?: break
            changed = true
            when (command) {
                Command.Tap       -> tapped = true
                Command.Undo      -> session.undo()
                Command.Finish    -> session.finish()
                Command.Reset     -> session.reset()
                is Command.Select -> session.select(command.tool)
            }
        }

        val width = viewportWidth
        val height = viewportHeight
        val projector = Projector(frame.viewMatrix, frame.projectionMatrix, width, height)
        val state = session.update(frame, projector, width / 2f, height / 2f, tapped)
        val overlay = state.project(projector, width, height)

        _uiState.update {
            it.copy(
                tool          = state.tool,
                confidence    = frame.confidence(),
                overlay       = overlay,
                readings      = state.solution.readings,
                accuracy      = state.solution.accuracy,
                hint          = state.hint,
                reticleLocked = state.reticleLocked,
                canUndo       = state.canUndo,
                canFinish     = state.canFinish,
                isComplete    = state.stage == Stage.COMPLETE,
                canSave       = state.canSave,
                isSaved       = it.isSaved && !changed,
            )
        }
    }

    private fun SessionState.project(projector: Projector, width: Int, height: Int) = MeasureOverlay(
        segments = solution.wire.mapNotNull { projector.projectSegment(it.a, it.b) },
        dots = dots.mapNotNull(projector::project),
        // Only labels the user can see: the screen decides from these whether
        // a figure is already readable or needs the readings table.
        labels = solution.labels.mapNotNull { label ->
            projector.project(label.at)
                ?.takeIf { it.x in 0f..width.toFloat() && it.y in 0f..height.toFloat() }
                ?.let { OverlayLabel(it, label.quantity, label.value, label.tolerance, label.grade) }
        },
    )

    /**
     * Session-level tracking quality for the HUD badge.
     *
     * A camera that is tracking but reporting a problem is recovering rather
     * than locked, so it is reported as LOW — not HIGH.
     */
    private fun ArFrame.confidence(): TrackingConfidence = when (status) {
        TrackingStatus.TRACKING ->
            if (issue == TrackingIssue.NONE) TrackingConfidence.HIGH else TrackingConfidence.LOW
        TrackingStatus.PAUSED  -> TrackingConfidence.LOW
        TrackingStatus.STOPPED -> TrackingConfidence.NOT_TRACKING
    }

    // ── User actions (main thread) ───────────────────────────────────────────

    /** Places whatever the reticle is on, or starts over if the shape is finished. */
    fun tap() {
        commands += Command.Tap
    }

    fun undo() {
        commands += Command.Undo
    }

    /** Ends an open-ended shape, or keeps a box or cylinder flat. */
    fun finish() {
        commands += Command.Finish
    }

    fun reset() {
        commands += Command.Reset
    }

    fun selectTool(tool: MeasureTool) {
        // Shown at once so the chip responds even before the next camera frame.
        _uiState.update { it.copy(tool = tool) }
        commands += Command.Select(tool)
    }

    /** Cycles the active unit display: CM → M → IN → FT → CM. */
    fun cycleUnit() {
        _uiState.update { it.copy(unitSystem = it.unitSystem.next()) }
    }

    /**
     * Persists the finished shape together with its error bounds.
     *
     * Refuses when the accuracy is unverifiable — storing a figure whose error
     * we couldn't bound would put an unqualified number in the saved list.
     */
    fun saveMeasurement(label: String = "") {
        val snapshot = _uiState.value
        if (!snapshot.canSave || snapshot.isSaving || snapshot.isSaved) return
        val accuracy = snapshot.accuracy ?: return
        val length = snapshot.readings.firstOrNull { it.quantity == Quantity.LENGTH }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            repository.save(
                MeasurementEntity(
                    id              = UUID.randomUUID().toString(),
                    distanceMeters  = length?.value ?: 0f,
                    unit            = snapshot.unitSystem.name,
                    label           = label.ifBlank { null },
                    toleranceMeters = length?.tolerance ?: 0f,
                    accuracyGrade   = accuracy.grade.name,
                    createdAt       = System.currentTimeMillis(),
                    kind            = snapshot.tool.name,
                    readings        = ReadingsJson.encode(snapshot.readings),
                )
            )
            _uiState.update { it.copy(isSaving = false, isSaved = true) }
        }
    }

    override fun onCleared() {
        session.release()
        super.onCleared()
    }
}

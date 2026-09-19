package com.tape.measure.ui.screens.measure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.TrackingState
import com.tape.measure.domain.model.AccuracyEstimate
import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.model.UnitSystem
import com.tape.measure.ui.theme.AmberBottom
import com.tape.measure.ui.theme.AmberTop
import com.tape.measure.ui.theme.ConfidenceHigh
import com.tape.measure.ui.theme.ConfidenceLow
import com.tape.measure.ui.theme.ConfidenceMedium
import com.tape.measure.ui.theme.InkBackground
import com.tape.measure.ui.theme.TapeMeasurement
import io.github.sceneview.ar.ARScene
import kotlin.math.roundToInt

/**
 * Core AR measurement screen.
 *
 * Layout (all fullscreen, stacked in a Box):
 *   1. [ARScene]          — camera feed + plane-detection grid  (SceneView)
 *   2. [MeasurementCanvas]— amber dots + connecting line + distance pill
 *   3. [TopHud]           — confidence badge (left) + unit chip + nav icons (right)
 *   4. [BottomControls]   — phase-aware hint / Reset / Save buttons
 *
 * Threading:
 *   ARScene calls [MeasureViewModel.onFrame] on the GL render thread.
 *   All other callbacks land on the main thread.
 *   See MeasureViewModel KDoc for the full threading contract.
 */
@Composable
fun MeasureScreen(
    onNavigateToSaved: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDeviceUnsupported: () -> Unit,
    viewModel: MeasureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Pixel dimensions for the 3-D→2-D projection in the ViewModel.
    var viewportWidth  by remember { mutableStateOf(1) }
    var viewportHeight by remember { mutableStateOf(1) }


    // Pre-compute pixel sizes used in both Canvas and positional offsets.
    val density = LocalDensity.current
    val dotRadiusPx    = with(density) { 10.dp.toPx() }
    val strokeWidthPx  = with(density) { 2.5.dp.toPx() }
    val dotOutlinePx   = with(density) { 2.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val w = coords.size.width
                val h = coords.size.height
                if (w != viewportWidth || h != viewportHeight) {
                    viewportWidth  = w
                    viewportHeight = h
                    viewModel.setViewport(w, h)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> viewModel.enqueueTap(offset.x, offset.y) }
            },
    ) {

        // ── 1. ARScene (camera background + plane grid) ──────────────────────
        ARScene(
            modifier = Modifier.fillMaxSize(),
            planeRenderer = true,
            onSessionCreated = { _ -> },
            onSessionUpdated = { _, frame ->
                viewModel.onFrame(frame.camera, frame)
            },
            onSessionFailed = { _ -> onDeviceUnsupported() },
        )

        // ── 2. Measurement overlay (Canvas) ──────────────────────────────────
        MeasurementCanvas(
            modifier       = Modifier.fillMaxSize(),
            screenPointA   = uiState.screenPointA,
            screenPointB   = uiState.screenPointB,
            dotRadiusPx    = dotRadiusPx,
            dotOutlinePx   = dotOutlinePx,
            strokeWidthPx  = strokeWidthPx,
        )

        // ── Distance pill at midpoint (fades in when both points are set) ──
        val pA   = uiState.screenPointA
        val pB   = uiState.screenPointB
        val dist = uiState.distanceMeters
        val midX = if (pA != null && pB != null) ((pA.x + pB.x) / 2f).roundToInt() else 0
        val midY = if (pA != null && pB != null)
            ((pA.y + pB.y) / 2f).roundToInt() - with(density) { 34.dp.roundToPx() } else 0
        AnimatedVisibility(
            visible  = pA != null && pB != null && dist != null,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(midX, midY) },
        ) {
            DistancePill(
                text      = dist?.let { uiState.unitSystem.format(it) } ?: "",
                tolerance = uiState.accuracy?.formatTolerance(uiState.unitSystem),
                grade     = uiState.accuracy?.grade,
            )
        }

        // ── 3. Top HUD ────────────────────────────────────────────────────────
        TopHud(
            modifier             = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            confidence           = uiState.confidence,
            accuracy             = uiState.accuracy,
            unitSystem           = uiState.unitSystem,
            currentUnitLabel     = uiState.unitSystem.label(),
            onUnitTap            = { viewModel.cycleUnit() },
            onNavigateToSaved    = onNavigateToSaved,
            onNavigateToSettings = onNavigateToSettings,
        )

        // ── 4. Bottom controls ────────────────────────────────────────────────
        BottomControls(
            modifier    = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            phase       = uiState.phase,
            isSaving    = uiState.isSaving,
            accuracy    = uiState.accuracy,
            onReset     = { viewModel.reset() },
            onSave      = { viewModel.saveMeasurement() },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Canvas overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MeasurementCanvas(
    screenPointA:  Offset?,
    screenPointB:  Offset?,
    dotRadiusPx:   Float,
    dotOutlinePx:  Float,
    strokeWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (screenPointA == null && screenPointB == null) return@Canvas

        // Connecting line (drawn first so dots sit on top)
        if (screenPointA != null && screenPointB != null) {
            drawLine(
                color       = Color.White.copy(alpha = 0.75f),
                start       = screenPointA,
                end         = screenPointB,
                strokeWidth = strokeWidthPx,
                cap         = StrokeCap.Round,
            )
        }

        // Dot A
        if (screenPointA != null) {
            // Outer ring
            drawCircle(
                color  = Color.White.copy(alpha = 0.6f),
                radius = dotRadiusPx + dotOutlinePx,
                center = screenPointA,
            )
            // Filled core
            drawCircle(
                color  = AmberBottom,
                radius = dotRadiusPx,
                center = screenPointA,
            )
        }

        // Dot B (same style as A — both endpoints look identical)
        if (screenPointB != null) {
            drawCircle(
                color  = Color.White.copy(alpha = 0.6f),
                radius = dotRadiusPx + dotOutlinePx,
                center = screenPointB,
            )
            drawCircle(
                color  = AmberBottom,
                radius = dotRadiusPx,
                center = screenPointB,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Distance pill
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Distance readout at the measurement midpoint.
 *
 * Confidence-Forward treatment (execution.md → "Default AR variation = 1c"):
 * the tolerance sits directly under the value in the grade's colour, so the
 * user reads "2.10 m ±2.3 cm" as one unit rather than having to hunt for an
 * accuracy indicator elsewhere on screen.
 */
@Composable
private fun DistancePill(
    text: String,
    tolerance: String?,
    grade: AccuracyGrade?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier  = modifier,
        shape     = RoundedCornerShape(8.dp),
        color     = InkBackground.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier            = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text      = text,
                style     = TapeMeasurement.Medium,
                color     = AmberTop,
                textAlign = TextAlign.Center,
            )
            if (tolerance != null) {
                Text(
                    text      = tolerance,
                    style     = MaterialTheme.typography.bodySmall,
                    color     = grade.tint(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Maps an accuracy grade to its badge colour. Null / unknown reads as muted. */
@Composable
private fun AccuracyGrade?.tint(): Color = when (this) {
    AccuracyGrade.HIGH        -> ConfidenceHigh
    AccuracyGrade.FAIR        -> ConfidenceMedium
    AccuracyGrade.LOW         -> ConfidenceLow
    AccuracyGrade.UNAVAILABLE -> ConfidenceLow
    AccuracyGrade.UNKNOWN,
    null                      -> Color.White.copy(alpha = 0.6f)
}

// ─────────────────────────────────────────────────────────────────────────────
// Top HUD: confidence badge + unit chip + nav icons
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopHud(
    confidence:           TrackingConfidence,
    accuracy:             AccuracyEstimate?,
    unitSystem:           UnitSystem,
    currentUnitLabel:     String,
    onUnitTap:            () -> Unit,
    onNavigateToSaved:    () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.Top,
    ) {
        // Confidence badge (left)
        ConfidenceBadge(
            confidence = confidence,
            accuracy   = accuracy,
            unitSystem = unitSystem,
        )

        // Unit chip + nav icons (right)
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnitChip(label = currentUnitLabel, onClick = onUnitTap)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onNavigateToSaved) {
                Icon(
                    imageVector        = Icons.Outlined.List,
                    contentDescription = "Saved measurements",
                    tint               = Color.White.copy(alpha = 0.8f),
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector        = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint               = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * Session-tracking badge.
 *
 * Before both points are placed it reports session tracking only. Once a
 * measurement exists it reports the READING's grade and real tolerance instead,
 * because that is what the user is actually deciding on.
 *
 * It no longer prints a fixed "±1 cm" — every figure here comes from
 * AccuracyEngine and moves with distance, placement range and tracking state.
 */
@Composable
private fun ConfidenceBadge(
    confidence: TrackingConfidence,
    accuracy: AccuracyEstimate?,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val hasReading = accuracy != null

    val dotColor = if (hasReading) {
        accuracy!!.grade.tint()
    } else {
        when (confidence) {
            TrackingConfidence.HIGH         -> ConfidenceHigh
            TrackingConfidence.LOW          -> ConfidenceMedium
            TrackingConfidence.NOT_TRACKING -> ConfidenceLow
        }
    }

    val title = if (hasReading) accuracy!!.grade.label() else confidence.label()

    // Secondary line: the real tolerance when we have one, otherwise nothing.
    // Deliberately blank rather than a placeholder — we don't fill space with
    // a number we haven't earned.
    val detail = accuracy?.formatTolerance(unitSystem)

    Surface(
        modifier       = modifier.widthIn(max = 220.dp),
        shape          = RoundedCornerShape(20.dp),
        color          = InkBackground.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text  = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                if (detail != null) {
                    Text(
                        text  = detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.85f,
                        ),
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitChip(
    label:   String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier  = modifier,
        shape     = RoundedCornerShape(20.dp),
        color     = InkBackground.copy(alpha = 0.72f),
        onClick   = onClick,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelLarge,
            color    = AmberTop,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom controls: hint text, Reset, Save
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomControls(
    phase:    MeasurePhase,
    isSaving: Boolean,
    accuracy: AccuracyEstimate?,
    onReset:  () -> Unit,
    onSave:   () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier              = modifier,
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(12.dp),
    ) {
        // Once a reading exists, the accuracy explanation replaces the generic
        // hint — it's strictly more useful and tells the user what to change.
        val hint = accuracy?.reason ?: when (phase) {
            MeasurePhase.IDLE    -> "Point at a flat surface, then tap to place the first point."
            MeasurePhase.POINT_A -> "First point set. Tap to place the second point."
            MeasurePhase.BOTH    -> "Tap anywhere to start a new measurement."
        }
        Text(
            text      = hint,
            style     = MaterialTheme.typography.bodySmall,
            color     = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier  = Modifier
                .background(InkBackground.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Reset + Save only appear once both points are placed
        AnimatedVisibility(
            visible = phase == MeasurePhase.BOTH,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier              = Modifier.fillMaxWidth(),
            ) {
                // Reset button
                Button(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape    = MaterialTheme.shapes.medium,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        contentColor   = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Reset", style = MaterialTheme.typography.labelLarge)
                }

                // Save is blocked while the error can't be bounded: persisting a
                // distance with no verifiable tolerance would put an unqualified
                // number in the saved list.
                val canSave = accuracy != null &&
                    accuracy.grade != AccuracyGrade.UNAVAILABLE
                Button(
                    onClick  = onSave,
                    enabled  = !isSaving && canSave,
                    modifier = Modifier.weight(1f),
                    shape    = MaterialTheme.shapes.medium,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = AmberBottom,
                        contentColor   = InkBackground,
                    ),
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Bookmark,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = if (isSaving) "Saving…" else "Save",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

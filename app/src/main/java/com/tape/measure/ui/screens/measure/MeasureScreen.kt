package com.tape.measure.ui.screens.measure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tape.measure.ar.ArCoreFrame
import com.tape.measure.ar.configureForMeasuring
import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.model.UnitSystem
import com.tape.measure.domain.shape.MeasureTool
import com.tape.measure.domain.shape.Reading
import com.tape.measure.domain.shape.ShapeAccuracy
import com.tape.measure.domain.shape.format
import com.tape.measure.domain.shape.formatTolerance
import com.tape.measure.domain.shape.formatValue
import com.tape.measure.sim.SimCamera
import com.tape.measure.sim.SimFrame
import com.tape.measure.sim.SimWorld
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
 * The measurement screen.
 *
 * Layout (all fullscreen, stacked in a Box):
 *   1. The world       — the camera feed ([ARScene]), or the practice room
 *   2. [ShapeOverlay]  — the shape in progress, its points, and the reticle
 *   3. [DimensionLabel]s — each edge's length, next to the edge
 *   4. [TopHud]        — accuracy badge (left) + unit chip + nav icons (right)
 *   5. [BottomControls] — hint, readings, tool picker, actions
 *
 * Points go where the centre reticle is aiming; a tap anywhere places one.
 *
 * @param simulated true to measure the practice room instead of the camera
 *        feed — the only option where ARCore can't run.
 */
@Composable
fun MeasureScreen(
    simulated: Boolean,
    onNavigateToSaved: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDeviceUnsupported: () -> Unit,
    viewModel: MeasureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var simCamera by remember { mutableStateOf(SimCamera.START) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                viewport = it
                viewModel.setViewport(it.width, it.height)
            },
    ) {

        // ── 1. The world ─────────────────────────────────────────────────────
        if (simulated) {
            val world = remember { SimWorld.practiceRoom() }
            PracticeRoomView(world, simCamera, Modifier.fillMaxSize())
            LaunchedEffect(world) {
                while (true) {
                    withFrameNanos { }
                    val size = viewport
                    if (size.width > 0 && size.height > 0) {
                        viewModel.onFrame(SimFrame(world, simCamera, size.width, size.height))
                    }
                }
            }
        } else {
            ARScene(
                modifier = Modifier.fillMaxSize(),
                // No dot grid over detected surfaces: the reticle turning
                // amber already says "this is a surface", with less clutter.
                planeRenderer = false,
                sessionConfiguration = ::configureForMeasuring,
                onSessionUpdated = { session, frame -> viewModel.onFrame(ArCoreFrame(session, frame)) },
                onSessionFailed = { _ -> onDeviceUnsupported() },
            )
        }

        // ── 2. Shape overlay ─────────────────────────────────────────────────
        // Touches are caught here, on the layer above the world, not on the
        // outer Box: ARScene is an Android View and consumes every touch it
        // sees, so a listener behind it never receives the tap.
        ShapeOverlay(
            overlay = uiState.overlay,
            reticleLocked = uiState.reticleLocked,
            showReticle = !uiState.isComplete,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (simulated) {
                        Modifier.lookOrTap(
                            onLook = { yaw, pitch -> simCamera = simCamera.look(yaw, pitch) },
                            onTap = { viewModel.tap() },
                        )
                    } else {
                        Modifier.pointerInput(Unit) { detectTapGestures { viewModel.tap() } }
                    }
                ),
        )

        // ── 3. Dimension labels ──────────────────────────────────────────────
        // A shape with one figure has one label, which is the headline: it is
        // drawn large, with its tolerance. Several share the screen, so they
        // are drawn compact and the readings table carries the tolerances.
        val prominent = uiState.headlineOnLabel
        uiState.overlay.labels.forEachIndexed { index, label ->
            key(index) {
                DimensionLabel(
                    label = label,
                    unitSystem = uiState.unitSystem,
                    prominent = prominent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                label.at.x.roundToInt(),
                                label.at.y.roundToInt() - (if (prominent) 34.dp else 16.dp).roundToPx(),
                            )
                        }
                        .centredOnOrigin(),
                )
            }
        }

        // ── 4. Top HUD ───────────────────────────────────────────────────────
        TopHud(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            confidence = uiState.confidence,
            accuracy = uiState.accuracy,
            readings = uiState.readings,
            unitSystem = uiState.unitSystem,
            simulated = simulated,
            onUnitTap = { viewModel.cycleUnit() },
            onNavigateToSaved = onNavigateToSaved,
            onNavigateToSettings = onNavigateToSettings,
        )

        if (simulated) {
            PracticeRoomMovePad(
                onMove = { forward, right, up -> simCamera = simCamera.move(forward, right, up) },
                // Under the HUD, over the far wall: the part of the view with
                // the least in it to measure.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 72.dp, end = 12.dp),
            )
        }

        // ── 5. Bottom controls ───────────────────────────────────────────────
        BottomControls(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 16.dp),
            uiState = uiState,
            onSelectTool = viewModel::selectTool,
            onUndo = viewModel::undo,
            onReset = viewModel::reset,
            onFinish = viewModel::finish,
            onSave = { viewModel.saveMeasurement(practice = simulated) },
        )
    }
}

/** Places content so its centre, not its top-left corner, sits at its position. */
private fun Modifier.centredOnOrigin(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) { placeable.place(-placeable.width / 2, -placeable.height / 2) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shape overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShapeOverlay(
    overlay: MeasureOverlay,
    reticleLocked: Boolean,
    showReticle: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = 2.5.dp.toPx()
        val dotRadius = 7.dp.toPx()
        val dotRing = 2.dp.toPx()

        // Lines first so dots sit on top.
        overlay.segments.forEach { segment ->
            drawLine(
                color = Color.White.copy(alpha = 0.75f),
                start = Offset(segment.a.x, segment.a.y),
                end = Offset(segment.b.x, segment.b.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        overlay.dots.forEach { dot ->
            val centre = Offset(dot.x, dot.y)
            drawCircle(Color.White.copy(alpha = 0.6f), dotRadius + dotRing, centre)
            drawCircle(AmberBottom, dotRadius, centre)
        }

        // Reticle: where the next tap will land. Amber once it is on something.
        if (showReticle) {
            val tint = if (reticleLocked) AmberTop else Color.White.copy(alpha = 0.5f)
            drawCircle(tint, 18.dp.toPx(), center, style = Stroke(width = 2.dp.toPx()))
            drawCircle(tint, 2.5.dp.toPx(), center)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dimension label
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A dimension written next to its edge.
 *
 * Confidence-Forward treatment (execution.md → "Default AR variation = 1c"):
 * the tolerance sits directly under the value in the grade's colour, so the
 * user reads "2.10 m ±2.3 cm" as one unit rather than having to hunt for an
 * accuracy indicator elsewhere on screen.
 */
@Composable
private fun DimensionLabel(
    label: OverlayLabel,
    unitSystem: UnitSystem,
    prominent: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = InkBackground.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (prominent) 12.dp else 8.dp,
                vertical = if (prominent) 6.dp else 3.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = unitSystem.format(label.quantity, label.value),
                style = if (prominent) TapeMeasurement.Medium else TapeMeasurement.Small,
                color = AmberTop,
                textAlign = TextAlign.Center,
            )
            // Several labels crowd each other, and the readings table already
            // carries every tolerance, so only a lone label repeats its own.
            if (prominent && label.tolerance != null) {
                Text(
                    text = "±" + unitSystem.format(label.quantity, label.tolerance, reference = label.value),
                    style = MaterialTheme.typography.bodySmall,
                    color = label.grade.tint(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Maps an accuracy grade to its badge colour. */
private fun AccuracyGrade.tint(): Color = when (this) {
    AccuracyGrade.HIGH        -> ConfidenceHigh
    AccuracyGrade.FAIR        -> ConfidenceMedium
    AccuracyGrade.LOW         -> ConfidenceLow
    AccuracyGrade.UNAVAILABLE -> ConfidenceLow
    AccuracyGrade.UNKNOWN     -> Color.White.copy(alpha = 0.6f)
}

// ─────────────────────────────────────────────────────────────────────────────
// Top HUD: accuracy badge + unit chip + nav icons
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopHud(
    confidence: TrackingConfidence,
    accuracy: ShapeAccuracy?,
    readings: List<Reading>,
    unitSystem: UnitSystem,
    simulated: Boolean,
    onUnitTap: () -> Unit,
    onNavigateToSaved: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfidenceBadge(
                confidence = confidence,
                accuracy = accuracy,
                // With one figure the badge can carry its tolerance; with
                // several, each figure shows its own in the table.
                detail = readings.singleOrNull()?.formatTolerance(unitSystem),
            )
            if (simulated) {
                Text(
                    text = "Practice room. These are not real measurements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .background(InkBackground.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // Unit chip + nav icons (right)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(label = unitSystem.label(), selected = false, onClick = onUnitTap)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onNavigateToSaved) {
                Icon(
                    imageVector = Icons.Outlined.List,
                    contentDescription = "Saved measurements",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * Before there is a reading the badge reports session tracking only. Once a
 * shape has one it reports the SHAPE's grade instead, because that is what the
 * user is actually deciding on.
 */
@Composable
private fun ConfidenceBadge(
    confidence: TrackingConfidence,
    accuracy: ShapeAccuracy?,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val dotColor = accuracy?.grade?.tint() ?: when (confidence) {
        TrackingConfidence.HIGH         -> ConfidenceHigh
        TrackingConfidence.LOW          -> ConfidenceMedium
        TrackingConfidence.NOT_TRACKING -> ConfidenceLow
    }
    val title = accuracy?.grade?.label() ?: confidence.label()

    Surface(
        modifier = modifier.widthIn(max = 220.dp),
        shape = RoundedCornerShape(20.dp),
        color = InkBackground.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                // Deliberately absent rather than a placeholder — we don't fill
                // space with a number we haven't earned.
                if (detail != null) {
                    Text(
                        text = detail,
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
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) AmberBottom else InkBackground.copy(alpha = 0.72f),
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) InkBackground else AmberTop,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom controls: hint, readings, tool picker, actions
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomControls(
    uiState: MeasureUiState,
    onSelectTool: (MeasureTool) -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (uiState.hint.isNotEmpty()) {
            Text(
                text = uiState.hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .background(InkBackground.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // A lone figure on a lone edge is already written beside it; anything
        // else needs the table, or its headline would appear nowhere. While an
        // object is still being scanned its numbers move every frame, and the
        // box's own labels already show them, so the table waits for Done.
        val scanning = uiState.tool == MeasureTool.OBJECT && !uiState.isComplete
        if (uiState.readings.isNotEmpty() && !uiState.headlineOnLabel && !scanning) {
            ReadingsCard(
                title = uiState.objectName,
                readings = uiState.readings,
                unitSystem = uiState.unitSystem,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        // Tools are picked before measuring, not during: once something is
        // placed the row gives way to the actions. Reset brings it back.
        if (!uiState.canUndo) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MeasureTool.entries.forEach { tool ->
                    Chip(
                        label = tool.label,
                        selected = tool == uiState.tool,
                        onClick = { onSelectTool(tool) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.canUndo,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                ActionButton("Undo", Icons.AutoMirrored.Outlined.Undo, onUndo, Modifier.weight(1f))
                ActionButton("Reset", Icons.Outlined.Refresh, onReset, Modifier.weight(1f))
                if (uiState.canFinish) {
                    ActionButton("Done", Icons.Outlined.Check, onFinish, Modifier.weight(1f), primary = true)
                }
                // Save is blocked while the error can't be bounded: persisting
                // a figure with no verifiable tolerance would put an
                // unqualified number in the saved list.
                if (uiState.isComplete) {
                    ActionButton(
                        text = when {
                            uiState.isSaving -> "Saving…"
                            uiState.isSaved  -> "Saved"
                            else             -> "Save"
                        },
                        icon = Icons.Outlined.Bookmark,
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        primary = true,
                        enabled = uiState.canSave && !uiState.isSaving && !uiState.isSaved,
                    )
                }
            }
        }
    }
}

/** Every figure the shape reports, each with its own error bound. */
@Composable
private fun ReadingsCard(
    title: String?,
    readings: List<Reading>,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = InkBackground.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            readings.forEachIndexed { index, reading ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = reading.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = reading.formatValue(unitSystem),
                        // The headline figure leads; the rest support it.
                        style = TapeMeasurement.Small,
                        color = if (index == 0) AmberTop else Color.White,
                    )
                    Text(
                        text = reading.formatTolerance(unitSystem) ?: "can't verify",
                        style = MaterialTheme.typography.bodySmall,
                        color = reading.grade.tint(),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = if (primary) {
            ButtonDefaults.buttonColors(containerColor = AmberBottom, contentColor = InkBackground)
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

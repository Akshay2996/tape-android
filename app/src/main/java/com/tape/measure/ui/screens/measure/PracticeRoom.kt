package com.tape.measure.ui.screens.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.tape.measure.domain.geometry.Point2
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.sim.SimBox
import com.tape.measure.sim.SimCamera
import com.tape.measure.sim.SimDrum
import com.tape.measure.sim.SimFrame
import com.tape.measure.sim.SimQuad
import com.tape.measure.sim.SimWorld
import com.tape.measure.ui.theme.InkBackground
import com.tape.measure.ui.theme.InkOutline
import com.tape.measure.ui.theme.InkSurface
import com.tape.measure.ui.theme.InkSurfaceHigh
import com.tape.measure.ui.theme.InkTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Degrees the camera turns per pixel dragged. Dragging pulls the room with the finger. */
private const val LOOK_DEGREES_PER_PIXEL = 0.06f

/** Metres walked per step while a move button is held. */
private const val MOVE_STEP_METERS = 0.04f
private const val MOVE_REPEAT_MILLIS = 40L

/** Spacing of the floor and wall grid lines. */
private const val GRID_METERS = 0.5f

/** Flat sides used to draw a drum. */
private const val DRUM_FACETS = 28

/** Where the room's one light comes from; only used to shade faces apart. */
private val LIGHT = Vec3(0.35f, 0.8f, 0.5f).normalized()!!

/**
 * Draws the practice room as seen from [camera].
 *
 * A stand-in for the camera feed where ARCore can't run. It is drawn with the
 * same [Projector] the measurement overlay uses, so the overlay lines up with
 * it exactly as it does with the real world on a phone.
 */
@Composable
fun PracticeRoomView(
    world: SimWorld,
    camera: SimCamera,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.background(InkBackground)) {
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas
        val projector = Projector(
            camera.viewMatrix(),
            Projector.perspective(SimFrame.FOV_Y_DEGREES, width.toFloat() / height),
            width,
            height,
        )

        world.ground.forEachIndexed { index, quad ->
            fill(projector, quad.corners, if (index == 0) InkSurface else InkSurfaceHigh)
            grid(projector, quad)
        }
        world.markings.forEach { line(projector, it.a, it.b, InkTextSecondary, 2.dp.toPx()) }

        // Far objects first, so nearer ones are painted over them.
        val solids = ArrayList<Pair<Float, () -> Unit>>()
        world.boxes.forEach { box ->
            solids += box.base.distanceTo(camera.position) to { drawBox(projector, camera.position, box) }
        }
        world.drums.forEach { drum ->
            solids += drum.base.distanceTo(camera.position) to { drawDrum(projector, camera.position, drum) }
        }
        solids.sortedByDescending { it.first }.forEach { it.second() }
    }
}

private fun DrawScope.drawBox(projector: Projector, eye: Vec3, box: SimBox) {
    box.faces.filter { it.faces(eye) }.forEach { face ->
        fill(projector, face.corners, shade(face.normal))
        outline(projector, face.corners, InkBackground.copy(alpha = 0.6f))
    }
}

private fun DrawScope.drawDrum(projector: Projector, eye: Vec3, drum: SimDrum) {
    val up = Vec3.UP * drum.height
    fun rim(i: Int): Vec3 {
        val angle = (2.0 * PI * i / DRUM_FACETS).toFloat()
        return drum.base + Vec3(cos(angle), 0f, sin(angle)) * drum.radius
    }
    for (i in 0 until DRUM_FACETS) {
        val a = rim(i)
        val b = rim(i + 1)
        val normal = ((a + b) * 0.5f - drum.base).normalized() ?: continue
        if ((normal dot (eye - a)) <= 0f) continue   // facing away
        fill(projector, listOf(a, b, b + up, a + up), shade(normal))
    }
    if (eye.y > drum.base.y + drum.height) {
        val top = (0 until DRUM_FACETS).map { rim(it) + up }
        fill(projector, top, shade(Vec3.UP))
        outline(projector, top, InkBackground.copy(alpha = 0.6f))
    }
}

private fun SimQuad.faces(eye: Vec3): Boolean = (normal dot (eye - centre)) > 0f

/** Lighter where a face is turned towards the light, so edges read without outlines. */
private fun shade(normal: Vec3): Color {
    val lit = (normal dot LIGHT).coerceIn(0f, 1f)
    return lerp(InkOutline, InkTextSecondary, 0.15f + 0.45f * lit)
}

private fun DrawScope.grid(projector: Projector, quad: SimQuad) {
    val color = InkOutline.copy(alpha = 0.7f)
    fun lines(across: Vec3, along: Vec3) {
        val steps = (2f * across.length / GRID_METERS).toInt()
        for (i in 0..steps) {
            val t = -1f + 2f * i / steps
            line(projector, quad.centre + across * t - along, quad.centre + across * t + along, color, 1.dp.toPx())
        }
    }
    lines(quad.halfU, quad.halfV)
    lines(quad.halfV, quad.halfU)
}

private fun DrawScope.fill(projector: Projector, corners: List<Vec3>, color: Color) {
    val path = projector.projectPolygon(corners).toPath() ?: return
    drawPath(path, color)
}

private fun DrawScope.outline(projector: Projector, corners: List<Vec3>, color: Color) {
    val path = projector.projectPolygon(corners).toPath() ?: return
    drawPath(path, color, style = Stroke(width = 1.dp.toPx()))
}

private fun DrawScope.line(projector: Projector, a: Vec3, b: Vec3, color: Color, width: Float) {
    val segment = projector.projectSegment(a, b) ?: return
    drawLine(
        color = color,
        start = Offset(segment.a.x, segment.a.y),
        end = Offset(segment.b.x, segment.b.y),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}

private fun List<Point2>.toPath(): Path? {
    if (size < 3) return null
    return Path().apply {
        moveTo(first().x, first().y)
        drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
}

/**
 * One finger does both jobs in the practice room: a drag turns the camera, a
 * touch that doesn't move is a tap. They are told apart here, in one detector,
 * so a drag can never also count as a tap.
 */
fun Modifier.lookOrTap(
    onLook: (deltaYawDegrees: Float, deltaPitchDegrees: Float) -> Unit,
    onTap: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var travelled = Offset.Zero
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // Something on top of the room (the tool picker scrolling, say)
            // has claimed this touch: it is neither a look nor a tap.
            if (change.isConsumed) break
            if (!change.pressed) {
                if (!dragging) onTap()
                break
            }
            val delta = change.positionChange()
            travelled += delta
            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) {
                // Apply everything since the finger went down, so no part of
                // the drag is swallowed by the slop.
                dragging = true
                onLook(-travelled.x * LOOK_DEGREES_PER_PIXEL, travelled.y * LOOK_DEGREES_PER_PIXEL)
            } else if (dragging) {
                onLook(-delta.x * LOOK_DEGREES_PER_PIXEL, delta.y * LOOK_DEGREES_PER_PIXEL)
            }
            if (dragging) change.consume()
        }
    }
}

/** Buttons for walking around the practice room. Each moves while it is held. */
@Composable
fun PracticeRoomMovePad(
    onMove: (forward: Float, right: Float, up: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MoveButton(Icons.Outlined.KeyboardArrowUp, "Walk forward") { onMove(MOVE_STEP_METERS, 0f, 0f) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MoveButton(Icons.Outlined.KeyboardArrowLeft, "Step left") { onMove(0f, -MOVE_STEP_METERS, 0f) }
            MoveButton(Icons.Outlined.KeyboardArrowDown, "Walk back") { onMove(-MOVE_STEP_METERS, 0f, 0f) }
            MoveButton(Icons.Outlined.KeyboardArrowRight, "Step right") { onMove(0f, MOVE_STEP_METERS, 0f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MoveButton(Icons.Outlined.VerticalAlignTop, "Raise the phone") { onMove(0f, 0f, MOVE_STEP_METERS) }
            Spacer(Modifier.size(44.dp))
            MoveButton(Icons.Outlined.VerticalAlignBottom, "Lower the phone") { onMove(0f, 0f, -MOVE_STEP_METERS) }
        }
    }
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    description: String,
    onStep: () -> Unit,
) {
    val step = rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(InkBackground.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    step.value()
                    val repeat = scope.launch {
                        while (true) {
                            delay(MOVE_REPEAT_MILLIS)
                            step.value()
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.8f),
        )
    }
}

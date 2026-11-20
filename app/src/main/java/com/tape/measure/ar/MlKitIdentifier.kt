package com.tape.measure.ar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.tape.measure.domain.ar.Identified
import com.tape.measure.domain.ar.PendingIdentification
import com.tape.measure.domain.geometry.Point2
import com.tape.measure.domain.geometry.ScreenBox
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "TapeIdentify"

/**
 * Maps camera-image pixels to view pixels: the camera image is the sensor's
 * landscape picture, the view is the portrait screen, cropped and scaled.
 * Captured while the frame is current, so it can be used after.
 */
class ImageToView(
    private val originX: Float, private val originY: Float,
    private val perImageXx: Float, private val perImageXy: Float,
    private val perImageYx: Float, private val perImageYy: Float,
) {
    fun toView(ix: Float, iy: Float) = Point2(
        originX + perImageXx * ix + perImageYx * iy,
        originY + perImageXy * ix + perImageYy * iy,
    )

    fun toImage(p: Point2): Pair<Float, Float> {
        val det = perImageXx * perImageYy - perImageYx * perImageXy
        val dx = p.x - originX
        val dy = p.y - originY
        return ((perImageYy * dx - perImageYx * dy) / det) to ((-perImageXy * dx + perImageXx * dy) / det)
    }

    companion object {
        /** From where image corners (0,0), (w,0) and (0,h) land in the view. */
        fun from(view: FloatArray, width: Int, height: Int) = ImageToView(
            originX = view[0], originY = view[1],
            perImageXx = (view[2] - view[0]) / width, perImageXy = (view[3] - view[1]) / width,
            perImageYx = (view[4] - view[0]) / height, perImageYy = (view[5] - view[1]) / height,
        )
    }
}

/**
 * Names the object under a tap and finds its outline, on the phone.
 *
 * Two bundled ML Kit models, no network:
 *  1. Object detection finds the objects in the frame as boxes. It doesn't
 *     care what they are, so it works for cartons and chairs alike.
 *  2. Image labelling names what is inside the box under the tap.
 *
 * Runs once per tap on a background thread; the measuring code polls the
 * result and carries on without it if it takes too long.
 */
object MlKitIdentifier {

    /** Labels less certain than this are not shown: a wrong name is worse than none. */
    private const val MIN_LABEL_CONFIDENCE = 0.6f

    /** Labels too vague to be worth showing. */
    private val VAGUE = setOf("Room", "Interior design", "Floor", "Wall", "Flooring", "Wood", "Rectangle", "Pattern")

    private val worker = Executors.newSingleThreadExecutor()

    private val detector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
        )
    }

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    /**
     * Copies what it needs out of [image] now — the caller closes it as soon
     * as this returns — and identifies in the background.
     *
     * @param tap the tapped point, in view pixels.
     */
    fun identify(image: Image, mapping: ImageToView, tap: Point2): PendingIdentification {
        val width = image.width
        val height = image.height
        val nv21 = toNv21(image)

        val result = AtomicReference<Identified?>(null)
        worker.execute {
            result.set(
                try {
                    identifyNow(nv21, width, height, mapping, tap)
                } catch (e: Exception) {
                    Log.w(TAG, "identification failed", e)
                    Identified(name = null, outline = null)
                }
            )
        }
        return PendingIdentification { result.get() }
    }

    private fun identifyNow(nv21: ByteArray, width: Int, height: Int, mapping: ImageToView, tap: Point2): Identified {
        val camera = decode(nv21, width, height)

        // The models expect an upright picture; the camera's is sideways in
        // portrait. Turn it so "up" on screen is up in the picture.
        val upright = Matrix().apply { postRotate(uprightDegrees(mapping)) }
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat()).also(upright::mapRect)
        upright.postTranslate(-bounds.left, -bounds.top)
        val picture = Bitmap.createBitmap(camera, 0, 0, width, height, upright, true)
        val back = Matrix().also(upright::invert)

        // Where the tap is in the upright picture.
        val (tx, ty) = mapping.toImage(tap)
        val tapInPicture = floatArrayOf(tx, ty).also(upright::mapPoints)

        // 1. The detected object under the tap: the smallest box holding it.
        val objects = Tasks.await(detector.process(InputImage.fromBitmap(picture, 0)))
        val hit = objects
            .map { it.boundingBox }
            .filter { it.contains(tapInPicture[0].toInt(), tapInPicture[1].toInt()) }
            .minByOrNull { it.width() * it.height() }

        // 2. Name what is in that box — or, with no box, around the tap.
        val crop = hit?.let { grown(it, 0.1f, picture) } ?: around(tapInPicture, picture)
        val cropped = Bitmap.createBitmap(picture, crop.left, crop.top, crop.width(), crop.height())
        val labels = Tasks.await(labeler.process(InputImage.fromBitmap(cropped, 0)))
        val name = labels
            .filter { it.confidence >= MIN_LABEL_CONFIDENCE && it.text !in VAGUE }
            .maxByOrNull { it.confidence }
            ?.text
        Log.i(TAG, "objects=${objects.size} hit=${hit != null} labels=${labels.take(3).map { "${it.text}:${"%.2f".format(it.confidence)}" }}")

        // The box back on screen, through the picture's rotation and the
        // camera-to-view mapping.
        val outline = hit?.let { box ->
            val corners = floatArrayOf(
                box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(), box.left.toFloat(), box.bottom.toFloat(),
            ).also(back::mapPoints)
            ScreenBox.around((0 until 4).map { mapping.toView(corners[2 * it], corners[2 * it + 1]) })
        }
        return Identified(name, outline)
    }

    /** Clockwise turn that makes the camera picture upright on this screen. */
    private fun uprightDegrees(mapping: ImageToView): Float {
        // "Up" on screen, in camera-image pixels.
        val (x0, y0) = mapping.toImage(Point2(0f, 1f))
        val (x1, y1) = mapping.toImage(Point2(0f, 0f))
        val ux = x1 - x0
        val uy = y1 - y0
        return when {
            abs(uy) >= abs(ux) -> if (uy < 0) 0f else 180f
            ux > 0             -> 270f   // up points right: turn anticlockwise
            else               -> 90f    // up points left: turn clockwise
        }
    }

    private fun grown(box: Rect, fraction: Float, picture: Bitmap): Rect {
        val dx = (box.width() * fraction).toInt()
        val dy = (box.height() * fraction).toInt()
        return Rect(
            max(0, box.left - dx), max(0, box.top - dy),
            min(picture.width, box.right + dx), min(picture.height, box.bottom + dy),
        )
    }

    /** A square around the tap, a third of the picture across. */
    private fun around(tap: FloatArray, picture: Bitmap): Rect {
        val half = min(picture.width, picture.height) / 6
        val cx = tap[0].toInt().coerceIn(half, picture.width - half)
        val cy = tap[1].toInt().coerceIn(half, picture.height - half)
        return Rect(cx - half, cy - half, cx + half, cy + half)
    }

    private fun decode(nv21: ByteArray, width: Int, height: Int): Bitmap {
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 90, jpeg)
        val bytes = jpeg.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** YUV_420_888 to NV21, honouring each plane's row and pixel strides. */
    private fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        val (yPlane, uPlane, vPlane) = image.planes

        var o = 0
        val y = yPlane.buffer
        for (row in 0 until height) {
            for (col in 0 until width) out[o++] = y.get(row * yPlane.rowStride + col * yPlane.pixelStride)
        }
        val u = uPlane.buffer
        val v = vPlane.buffer
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                out[o++] = v.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                out[o++] = u.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        return out
    }
}

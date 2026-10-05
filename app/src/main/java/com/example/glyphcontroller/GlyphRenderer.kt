package com.example.glyphcontroller

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixObject
import kotlin.math.roundToInt

/**
 * Turns n*n intensity buffers into frames on the physical Glyph Matrix.
 *
 * Brightness: the SDK has no global "matrix brightness" call. The documented per-object
 * brightness (0..255, GlyphMatrixObject.Builder.setBrightness) is the control we use, computed as
 *     255 x globalBrightness x animationBrightness x frameBrightness x stateBrightness.
 * The phone's normal screen brightness is never touched.
 *
 * Output path: app mode uses setAppMatrixFrame (documented for apps, system build 20250801+);
 * toy mode (when our Glyph Toy is bound) uses setMatrixFrame as Nothing's docs prescribe for toys.
 *
 * One Bitmap is reused for every frame; identical consecutive frames are not re-sent.
 */
class GlyphRenderer(
    private val context: Context,
    private val n: Int,
    private val onError: (String) -> Unit
) : GlyphAnimationEngine.FrameSink {

    @Volatile var globalBrightness = 0.7f
    @Volatile var toyMode = false
    @Volatile var timeoutCall = MatrixTimeoutCall.OFF
    /** Receives (pixels already scaled by brightness) for the in-app preview; null when UI is not visible. */
    @Volatile var previewSink: ((IntArray) -> Unit)? = null

    private val bitmap: Bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    private val argb = IntArray(n * n)
    private var lastHash = 0
    private var lastBrightness = -1
    private var hasLast = false
    private var failures = 0
    private var timeoutApplied = false

    override fun push(pixels: IntArray, relBrightness: Float, force: Boolean) {
        val b = (255f * relBrightness.coerceIn(0f, 1f) * globalBrightness.coerceIn(0f, 1f))
            .roundToInt().coerceIn(0, 255)

        previewSink?.let { sink ->
            val scaled = IntArray(pixels.size) { (pixels[it].coerceIn(0, 255) * b) / 255 }
            sink(scaled)
        }

        val hash = pixels.contentHashCode()
        if (!force && hasLast && hash == lastHash && b == lastBrightness) return

        if (!GlyphLink.isReady) {
            hasLast = false // controller re-pushes once the link is up
            return
        }

        for (i in pixels.indices) {
            val v = pixels[i].coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(argb, 0, n, 0, 0, n, n)

        val result = GlyphLink.use { gm ->
            if (!timeoutApplied && timeoutCall != MatrixTimeoutCall.OFF) {
                timeoutApplied = true
                gm.setGlyphMatrixTimeout(timeoutCall == MatrixTimeoutCall.TRUE)
            }
            val obj = GlyphMatrixObject.Builder()
                .setImageSource(bitmap)
                .setPosition(0, 0)
                .setScale(100)
                .setOrientation(0)
                .setBrightness(b)
                .build()
            val frame = GlyphMatrixFrame.Builder().addTop(obj).build(context)
            val data = frame.render()
            if (toyMode) gm.setMatrixFrame(data) else gm.setAppMatrixFrame(data)
        } ?: return

        result.onSuccess {
            failures = 0
            lastHash = hash
            lastBrightness = b
            hasLast = true
        }.onFailure { t ->
            hasLast = false
            failures++
            Log.w(TAG, "frame send failed ($failures)", t)
            if (failures == 5) {
                onError("Sending frames to the Glyph Matrix keeps failing: ${t.message}. Reconnecting.")
                GlyphLink.reconnect()
                failures = 0
            }
        }
    }

    /** Stop showing our content. */
    fun clear() {
        hasLast = false
        previewSink?.invoke(IntArray(n * n))
        GlyphLink.use { gm ->
            if (toyMode) gm.turnOff() else gm.closeAppMatrix()
        }?.onFailure { Log.w(TAG, "clear failed", it) }
    }

    /** Forget cached state so the next frame is always sent (e.g. after reconnect or mode switch). */
    fun invalidate() {
        hasLast = false
        timeoutApplied = false
    }

    private companion object {
        const val TAG = "GlyphRenderer"
    }
}

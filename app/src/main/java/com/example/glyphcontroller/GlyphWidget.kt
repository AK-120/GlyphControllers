package com.example.glyphcontroller

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget: a dot-matrix mirror of whatever the Glyph Matrix is showing, plus its state.
 * Tapping it opens [QuickMenuActivity].
 *
 * Widgets are RemoteViews: the launcher cannot run our drawing code, so it cannot animate on its own.
 * While the controller runs and the screen is on, a fresh snapshot is pushed up to about 8 times a second
 * (see [INTERVAL_MS]) - much smoother than before, but still a stepped preview, not a true animation.
 * Identical snapshots are skipped, so a static AOD costs almost nothing. Nothing is pushed with the screen
 * off or when no widget is placed.
 */
object GlyphWidgets {
    @Volatile var active = false
        private set

    /** Set by [GlyphRuntime] so it can attach/detach the frame feed when widgets appear/disappear. */
    var onActiveChanged: (() -> Unit)? = null

    @Volatile private var lastPixels: IntArray? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastRenderAt = 0L
    private var scheduled = false

    // One reusable bitmap/canvas/paints: updateAppWidget() copies the bitmap into a parcel before it returns.
    private val bitmap: Bitmap = Bitmap.createBitmap(BITMAP_PX, BITMAP_PX, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 34, 34) }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var lastKey = 0

    private const val INTERVAL_MS = 120L
    private const val BITMAP_PX = 144
    private const val GAIN = 1.8f

    fun refreshActive(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, GlyphWidgetProvider::class.java))
        val now = ids.isNotEmpty()
        if (now != active) {
            active = now
            onActiveChanged?.invoke()
        }
    }

    /** Called (on the engine thread) with the latest matrix picture. */
    fun onFrame(context: Context, pixels: IntArray) {
        lastPixels = pixels
        if (!GlyphRuntime.screenOn.value) return // nobody is looking at the home screen
        request(context.applicationContext)
    }

    /** State text changed (on/off, animation, state) - redraw soon even if no new frame arrives. */
    fun forceUpdate(context: Context) {
        if (!active) return
        request(context.applicationContext)
    }

    private fun request(ctx: Context) {
        handler.post {
            val now = SystemClock.uptimeMillis()
            val wait = lastRenderAt + INTERVAL_MS - now
            if (wait <= 0) {
                lastRenderAt = now
                update(ctx)
            } else if (!scheduled) {
                scheduled = true
                handler.postDelayed({
                    scheduled = false
                    lastRenderAt = SystemClock.uptimeMillis()
                    update(ctx)
                }, wait)
            }
        }
    }

    fun update(ctx: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val component = ComponentName(ctx, GlyphWidgetProvider::class.java)
        if (mgr.getAppWidgetIds(component).isEmpty()) return
        runCatching {
            val s = GlyphRuntime.settings.value
            val running = GlyphRuntime.serviceRunning.value
            val on = s.enabled
            val n = GlyphRuntime.matrixSize
            val showing = on && running
            val pixels = if (showing) lastPixels else null

            val animName = GlyphRuntime.activeAnimation.value.ifEmpty { s.aodAnimation }
            val label = GlyphRuntime.library.value[animName]?.label ?: animName
            val state = when {
                !on -> "OFF"
                !running -> "STARTING"
                GlyphRuntime.activeState.value == GlyphState.IDLE -> "AOD"
                else -> GlyphRuntime.activeState.value.name
            }
            // Nothing visible changed since the last push: skip the (comparatively expensive) widget update.
            val key = java.util.Objects.hash(pixels?.contentHashCode(), showing, label, state, s.globalBrightness)
            if (!force && key == lastKey) return
            lastKey = key
            val bmp = drawMatrix(pixels, n)
            val tap = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, QuickMenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val small = RemoteViews(ctx.packageName, R.layout.widget_small).apply {
                setImageViewBitmap(R.id.widget_matrix, bmp)
                setViewVisibility(R.id.widget_badge, if (showing) View.VISIBLE else View.INVISIBLE)
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            val wide = RemoteViews(ctx.packageName, R.layout.widget_wide).apply {
                setImageViewBitmap(R.id.widget_matrix, bmp)
                setTextViewText(R.id.widget_title, label)
                setTextViewText(R.id.widget_status, (if (showing) "● " else "○ ") + state)
                setTextViewText(R.id.widget_sub, "Brightness ${s.globalBrightness}%  ·  tap for quick setup")
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            val views = RemoteViews(mapOf(SizeF(110f, 110f) to small, SizeF(250f, 110f) to wide))
            mgr.updateAppWidget(component, views)
        }
    }

    private fun drawMatrix(px: IntArray?, n: Int): Bitmap {
        bitmap.eraseColor(Color.TRANSPARENT)
        val cell = BITMAP_PX / n.toFloat()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val cx = (x + 0.5f) * cell
                val cy = (y + 0.5f) * cell
                canvas.drawCircle(cx, cy, cell * 0.28f, offPaint)
                val v = px?.getOrNull(y * n + x) ?: 0
                if (v > 0) {
                    onPaint.alpha = (v * GAIN).toInt().coerceIn(40, 255)
                    canvas.drawCircle(cx, cy, cell * 0.38f, onPaint)
                }
            }
        }
        return bitmap
    }
}

class GlyphWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        GlyphRuntime.init(context)
        GlyphWidgets.refreshActive(context)
    }

    override fun onDisabled(context: Context) {
        GlyphWidgets.refreshActive(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        GlyphWidgets.refreshActive(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        GlyphRuntime.init(context)
        GlyphWidgets.refreshActive(context)
        GlyphWidgets.update(context, force = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle
    ) {
        GlyphWidgets.update(context, force = true)
    }
}

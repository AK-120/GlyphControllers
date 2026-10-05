package com.example.glyphcontroller

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Plays [GlyphAnimation]s: JSON frame sequences or procedural generators.
 *
 * - Everything runs on ONE coroutine on the single-thread scope you pass in, so there is no locking.
 * - Timing uses absolute deadlines (no drift) and suspends with delay() - there is no busy loop.
 * - Starting a new animation always cancels the previous one first.
 * - Frames are handed to a [FrameSink]; the sink must copy what it needs (buffers are reused).
 */
class GlyphAnimationEngine(
    private val scope: CoroutineScope,
    private val sink: FrameSink,
    private val n: Int,
    private val onError: (String) -> Unit
) {
    interface FrameSink {
        /** [relBrightness] is 0..1: animation x frame x state brightness (global brightness is applied by the sink). */
        fun push(pixels: IntArray, relBrightness: Float, force: Boolean)
    }

    @Volatile private var speed = 1f
    @Volatile private var stateBrightness = 1f
    @Volatile private var input = 0f
    @Volatile var heartbeatMs: Long = 0L

    private val paused = MutableStateFlow(false)
    private var job: Job? = null

    // "holding" = animation finished / static; last picture stays on the matrix.
    private var holding = false
    private var heldPixels: IntArray? = null
    private var heldRelBase = 1f

    var current: GlyphAnimation? = null
        private set

    val isPlaying: Boolean get() = job?.isActive == true

    /** True if the picture changes over time (used to decide whether a wake lock is worthwhile). */
    val isDynamic: Boolean
        get() = when (val a = current) {
            is ProceduralAnimation -> true
            is FramesAnimation -> a.steps.size > 1
            null -> false
        }

    fun play(
        animation: GlyphAnimation,
        speed: Float,
        stateBrightness: Float,
        initialInput: Float,
        input: Float,
        onComplete: (() -> Unit)? = null
    ) {
        stop()
        this.speed = speed.coerceIn(0.1f, 4f)
        this.stateBrightness = stateBrightness.coerceIn(0f, 1f)
        this.input = input
        current = animation
        paused.value = false
        job = scope.launch {
            try {
                when (animation) {
                    is FramesAnimation -> runFrames(animation)
                    is ProceduralAnimation -> runProcedural(animation, initialInput)
                }
                onComplete?.invoke()
                hold()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("Animation '${animation.name}' failed: ${t.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        current = null
        holding = false
        heldPixels = null
    }

    fun pause() { paused.value = true }
    fun resume() { paused.value = false }

    fun setInput(value: Float) { input = value }

    /** Applies new speed / brightness to whatever is playing, immediately. */
    fun updateLive(speed: Float, stateBrightness: Float) {
        this.speed = speed.coerceIn(0.1f, 4f)
        this.stateBrightness = stateBrightness.coerceIn(0f, 1f)
        scope.launch { repushIfHolding() }
    }

    /** Re-sends the held picture (e.g. after the Glyph service reconnected). */
    fun refresh() {
        scope.launch { repushIfHolding(force = true) }
    }

    // ------------------------------------------------------------------ frames

    private suspend fun runFrames(a: FramesAnimation) {
        val steps = a.steps
        if (steps.isEmpty()) return
        var loops = 0
        var deadline = SystemClock.uptimeMillis()
        while (true) {
            for (step in steps) {
                deadline = awaitUnpaused(deadline)
                val base = a.brightness * step.brightness
                sink.push(step.pixels, base * stateBrightness, false)
                heldPixels = step.pixels
                heldRelBase = base

                // One static frame that loops forever: nothing to time, just hold it.
                if (steps.size == 1 && a.loop && a.loopCount == 0) return

                val dur = max(MIN_STEP_MS, (step.durationMs / speed).toLong())
                deadline += dur
                val wait = deadline - SystemClock.uptimeMillis()
                if (wait > 0) delay(wait)
                else {
                    if (wait < -dur) deadline = SystemClock.uptimeMillis() // fell behind: resync instead of bursting
                    currentCoroutineContext().ensureActive()
                }
            }
            loops++
            if (!a.loop || (a.loopCount > 0 && loops >= a.loopCount)) return
        }
    }

    // -------------------------------------------------------------- procedural

    private suspend fun runProcedural(a: ProceduralAnimation, initialInput: Float) {
        val gen = ProceduralGenerators.create(a.generator, a.params, n)
        if (gen == null) {
            onError("Unknown generator '${a.generator}' in '${a.name}'")
            return
        }
        gen.start(initialInput)
        val frameMs = max(MIN_STEP_MS, (1000f / a.fps.coerceIn(1f, 60f)).toLong())
        val buf = IntArray(n * n)
        val startedAt = SystemClock.uptimeMillis()
        var last = startedAt
        var deadline = startedAt
        var simTime = 0f
        while (true) {
            val wasPaused = paused.value
            deadline = awaitUnpaused(deadline)
            val now = SystemClock.uptimeMillis()
            if (wasPaused) last = now
            val dt = ((now - last).coerceAtLeast(0L) / 1000f).coerceAtMost(0.25f)
            last = now
            simTime += dt * speed
            gen.render(simTime, dt * speed, input, buf)
            sink.push(buf, a.brightness * stateBrightness, false)

            if (!a.loop && now - startedAt >= a.durationMs) {
                heldPixels = buf.copyOf()
                heldRelBase = a.brightness
                return
            }
            deadline += frameMs
            val wait = deadline - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            else {
                if (wait < -frameMs) deadline = SystemClock.uptimeMillis()
                currentCoroutineContext().ensureActive()
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun awaitUnpaused(deadline: Long): Long {
        if (!paused.value) return deadline
        paused.first { !it }
        return SystemClock.uptimeMillis()
    }

    /** Finished one-shot or static picture: keep it on, optionally re-sending it as a heartbeat. */
    private suspend fun hold() {
        holding = true
        while (true) {
            val hb = heartbeatMs
            if (hb <= 0L) awaitCancellation()
            delay(hb)
            repushIfHolding(force = true)
        }
    }

    private fun repushIfHolding(force: Boolean = true) {
        if (!holding) return
        val px = heldPixels ?: return
        sink.push(px, heldRelBase * stateBrightness, force)
    }

    private companion object {
        const val MIN_STEP_MS = 16L
    }
}

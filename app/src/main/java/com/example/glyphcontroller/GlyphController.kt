package com.example.glyphcontroller

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Central state manager.
 *
 *   IDLE (custom AOD)  <-  CHARGING / VOLUME / NOTIFICATION overlays
 *
 * State is *derived*, never stored as a transition table: volume and notification are timed overlays,
 * charging lasts while a charger is connected. Every event just updates facts and calls [resolve],
 * which picks the highest-priority active state (order comes from [GlyphSettings.priority], IDLE is
 * always last). That is why "return to AOD / return to charging" needs no special handling.
 *
 * All methods are safe to call from any thread: they hop onto [scope], a single-thread scope shared
 * with the animation engine, so all mutable state below is confined to that thread.
 */
class GlyphController(
    private val scope: CoroutineScope,
    private val engine: GlyphAnimationEngine,
    private val renderer: GlyphRenderer,
    private val settingsProvider: () -> GlyphSettings,
    private val libraryProvider: () -> AnimationLibrary,
    /** (state, animationName) whenever what is shown changes. IDLE + "" = nothing shown. */
    private val onDisplay: (GlyphState, String, Boolean) -> Unit
) {
    private var screenOn = true
    private var charge: ChargeInfo? = null
    private var simCharge: ChargeInfo? = null

    private var volumePercent = 0
    private var volumePrev = 0
    private var volumeUntil = 0L
    private var notifUntil = 0L
    private var music: MusicInfo? = null

    private var volumeJob: Job? = null
    private var notifJob: Job? = null
    private var simChargeJob: Job? = null

    private var currentKey: String? = null

    // ------------------------------------------------------------------ events

    fun onVolumeChanged(percent: Int, previous: Int) {
        scope.launch {
            val s = settingsProvider()
            val now = SystemClock.uptimeMillis()
            // Continuing an ongoing volume session keeps its start point; a new one starts from the old level.
            volumePrev = if (now < volumeUntil) volumePercent else previous
            volumePercent = percent.coerceIn(0, 100)
            volumeUntil = now + s.volumeDurationMs
            volumeJob?.cancel()
            volumeJob = scope.launch {
                delay(s.volumeDurationMs.toLong() + 20L)
                resolve()
            }
            resolve()
        }
    }

    fun onChargeChanged(info: ChargeInfo?) {
        scope.launch {
            charge = info
            resolve()
        }
    }

    fun onNotification() {
        scope.launch {
            val s = settingsProvider()
            if (!s.notificationEnabled) return@launch
            val now = SystemClock.uptimeMillis()
            notifUntil = now + s.notificationDurationMs
            notifJob?.cancel()
            notifJob = scope.launch {
                delay(s.notificationDurationMs.toLong() + 20L)
                resolve()
            }
            // Restart the one-shot even if it is already showing.
            if (currentKey?.startsWith(GlyphState.NOTIFICATION.name) == true) currentKey = null
            resolve()
        }
    }

    /** Track that is playing now, or null when nothing plays. Only affects the AOD (IDLE) picture. */
    fun onMusicChanged(info: MusicInfo?) {
        scope.launch {
            music = info
            resolve()
        }
    }

    fun onScreenChanged(on: Boolean) {
        scope.launch {
            screenOn = on
            resolve()
        }
    }

    /** Call after settings or the animation library changed, after (re)connecting, or on a toy AOD tick. */
    fun refresh(forceResend: Boolean = false) {
        scope.launch {
            if (forceResend) {
                renderer.invalidate()
                engine.refresh()
            }
            resolve()
        }
    }

    /** Output path changed (our Glyph Toy bound/unbound). */
    fun setToyMode(toy: Boolean) {
        scope.launch {
            if (renderer.toyMode != toy) {
                renderer.toyMode = toy
                renderer.invalidate()
                currentKey = null
                resolve()
            }
        }
    }

    // ------------------------------------------------------------- test hooks

    fun simulateVolume(percent: Int) {
        scope.launch { onVolumeChanged(percent, volumePercent) }
    }

    fun simulateCharging(kind: ChargeKind, level: Int, forMs: Long = 8_000L) {
        scope.launch {
            simCharge = ChargeInfo(PlugType.AC, level.coerceIn(0, 100), kind == ChargeKind.FULL, kind == ChargeKind.FAST)
            simChargeJob?.cancel()
            simChargeJob = scope.launch {
                delay(forMs)
                simCharge = null
                resolve()
            }
            resolve()
        }
    }

    fun shutdown() {
        scope.launch {
            volumeJob?.cancel(); notifJob?.cancel(); simChargeJob?.cancel()
            engine.stop()
            renderer.clear()
            currentKey = null
            onDisplay(GlyphState.IDLE, "", false)
        }
    }

    // --------------------------------------------------------------- resolving

    private data class Plan(
        val state: GlyphState, val anim: GlyphAnimation, val speed: Float, val brightness: Float,
        val input: Float, val initial: Float,
        /** Extra identity so a new track restarts the same animation (not part of the animation name). */
        val keySuffix: String = ""
    )

    private fun resolve() {
        val s = settingsProvider()
        if (!s.enabled) {
            stopOutput()
            return
        }
        val now = SystemClock.uptimeMillis()
        val ch = simCharge ?: charge
        val active = { state: GlyphState ->
            when (state) {
                GlyphState.VOLUME -> now < volumeUntil
                GlyphState.CHARGING -> ch?.pluggedIn == true
                GlyphState.NOTIFICATION -> s.notificationEnabled && now < notifUntil
                GlyphState.IDLE -> false
            }
        }
        val target = s.priority.order.firstOrNull(active) ?: GlyphState.IDLE
        if (target == GlyphState.IDLE && s.aodOnlyWhenScreenOff && screenOn) {
            stopOutput()
            return
        }

        val plan = plan(target, s, ch)
        engine.heartbeatMs = s.keepAliveSec * 1000L
        renderer.globalBrightness = s.globalBrightness / 100f
        renderer.timeoutCall = s.matrixTimeoutCall

        val key = "${plan.state.name}|${plan.anim.name}${plan.keySuffix}"
        if (key == currentKey && engine.isPlaying) {
            // Same animation: just feed it new values (duplicate requests never restart it).
            engine.updateLive(plan.speed, plan.brightness)
            engine.setInput(plan.input)
            return
        }
        currentKey = key
        engine.play(plan.anim, plan.speed, plan.brightness, plan.initial, plan.input)
        onDisplay(plan.state, plan.anim.name, engine.isDynamic)
    }

    private fun plan(target: GlyphState, s: GlyphSettings, ch: ChargeInfo?): Plan {
        val lib = libraryProvider()
        return when (target) {
            GlyphState.VOLUME -> {
                val base = pick(lib, s.volumeAnimation, "volume")
                val anim = if (volumePercent >= 100) variant(lib, base, "max") else base
                Plan(target, anim, s.volumeSpeed, s.volumeBrightness / 100f,
                    volumePercent / 100f, volumePrev / 100f)
            }
            GlyphState.CHARGING -> {
                val base = pick(lib, s.chargingAnimation, "charging")
                val kind = ch?.kind ?: ChargeKind.NORMAL
                val anim = if (kind == ChargeKind.NORMAL) base else variant(lib, base, kind.variantKey)
                val level = (ch?.levelPercent ?: 0) / 100f
                Plan(target, anim, s.chargingSpeed, s.chargingBrightness / 100f, level, level)
            }
            GlyphState.NOTIFICATION -> Plan(
                target, pick(lib, s.notificationAnimation, "notification"), 1f,
                s.notificationBrightness / 100f, 0f, 0f
            )
            GlyphState.IDLE -> {
                val m = music
                if (s.musicAodEnabled && m != null && m.playing) musicPlan(target, m, s)
                else Plan(target, pick(lib, s.aodAnimation, "aod"), s.aodSpeed, s.aodBrightness / 100f, 0f, 0f)
            }
        }
    }

    /** "Title - Artist" scrolls once, then a rotating disc shows for musicNameEverySec; repeat. */
    private fun musicPlan(target: GlyphState, m: MusicInfo, s: GlyphSettings): Plan {
        val text = listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" - ")
        val showName = s.musicShowName && text.isNotBlank()
        val anim = ProceduralAnimation(
            "Now playing", "Now playing", "aod", true, 0, 20f, 1f, emptyMap(), "music_aod",
            Params(mapOf("text" to text, "showName" to showName, "gap" to s.musicNameEverySec.toFloat())), 1500L
        )
        // speed stays 1: the scroll timing and the gap are defined in real seconds
        return Plan(target, anim, 1f, s.aodBrightness / 100f, 0f, 0f,
            keySuffix = "|$text|$showName|${s.musicNameEverySec}")
    }

    private fun pick(lib: AnimationLibrary, name: String, role: String): GlyphAnimation =
        lib[name] ?: lib.byRole(role).firstOrNull() ?: FallbackAnimations.forRole(role)

    private fun variant(lib: AnimationLibrary, base: GlyphAnimation, key: String): GlyphAnimation =
        base.variants[key]?.let { lib[it] } ?: base

    private fun stopOutput() {
        if (currentKey != null || engine.isPlaying) {
            engine.stop()
            renderer.clear()
            currentKey = null
            onDisplay(GlyphState.IDLE, "", false)
        }
    }
}

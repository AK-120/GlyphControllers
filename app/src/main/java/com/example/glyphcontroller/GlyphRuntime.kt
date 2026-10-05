package com.example.glyphcontroller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Process-wide runtime shared by the UI, the foreground service and the Glyph Toy.
 *
 * Owners ([Owner]) attach/detach; the engine, monitors and SDK link exist while at least one owner is attached.
 * UI-facing state is exposed as StateFlows.
 */
object GlyphRuntime {
    private const val TAG = "GlyphRuntime"

    enum class Owner { SERVICE, TOY }

    private lateinit var app: Context
    private var initialised = false

    /** Long-lived scope for housekeeping (settings persistence, reconnects). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single dedicated thread: animation timing, rendering and controller state all live here.
    private val engineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "glyph-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    // ---- UI-facing state
    val settings = MutableStateFlow(GlyphSettings())
    val settingsLoaded = MutableStateFlow(false)
    val library = MutableStateFlow(AnimationLibrary.EMPTY)
    val errors = MutableStateFlow<List<String>>(emptyList())
    val activeState = MutableStateFlow(GlyphState.IDLE)
    val activeAnimation = MutableStateFlow("")
    val animating = MutableStateFlow(false)
    val screenOn = MutableStateFlow(true)
    val serviceRunning = MutableStateFlow(false)
    val toyBound = MutableStateFlow(false)
    val preview = MutableStateFlow(IntArray(0))

    val linkStatus: StateFlow<GlyphLink.Status> get() = GlyphLink.status
    val linkDetail: StateFlow<String> get() = GlyphLink.detail

    var matrixSize = 13
        private set

    @Volatile var controller: GlyphController? = null
        private set

    private lateinit var repo: SettingsRepository
    private var persistJob: Job? = null
    private val owners = mutableSetOf<Owner>()
    private var renderer: GlyphRenderer? = null
    private var engine: GlyphAnimationEngine? = null
    private var volumeMonitor: VolumeMonitor? = null
    private var chargingMonitor: ChargingMonitor? = null
    private var screenMonitor: ScreenMonitor? = null
    private var reconnectJob: Job? = null
    @Volatile private var previewUsers = 0
    private var linkHeld = false

    /** One sink feeds both the in-app preview and the home-screen widget. */
    private val frameSink: (IntArray) -> Unit = { px ->
        if (previewUsers > 0) preview.value = px
        if (GlyphWidgets.active) GlyphWidgets.onFrame(app, px)
    }

    private fun applySink() {
        renderer?.previewSink = if (previewUsers > 0 || GlyphWidgets.active) frameSink else null
    }

    // ------------------------------------------------------------------ setup

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        repo = SettingsRepository(app)
        matrixSize = GlyphLink.matrixLength()
        preview.value = IntArray(matrixSize * matrixSize)
        appScope.launch {
            settings.value = repo.load()
            settingsLoaded.value = true
            controller?.refresh()
        }
        reloadLibrary()

        // Home-screen widget: attach the frame feed only while a widget exists, and redraw its texts on changes.
        GlyphWidgets.onActiveChanged = { applySink() }
        GlyphWidgets.refreshActive(app)
        appScope.launch {
            combine(settings, serviceRunning, activeState, activeAnimation, screenOn) { s, running, st, anim, on ->
                listOf(s.enabled, s.aodAnimation, s.globalBrightness, running, st, anim, on)
            }.distinctUntilChanged().collect { GlyphWidgets.forceUpdate(app) }
        }
        // Re-apply the picture whenever the SDK (re)connects; retry if the link drops while owned.
        appScope.launch {
            var previous = GlyphLink.Status.IDLE
            GlyphLink.status.collect { st ->
                if (st == GlyphLink.Status.CONNECTED) {
                    reconnectJob?.cancel()
                    renderer?.invalidate()
                    controller?.refresh(forceResend = true)
                } else if (st == GlyphLink.Status.DISCONNECTED && previous == GlyphLink.Status.CONNECTED) {
                    scheduleReconnect()
                }
                previous = st
            }
        }
    }

    fun reloadLibrary() {
        appScope.launch {
            val result = JsonAnimationLoader.load(app, matrixSize)
            library.value = result.library
            errors.value = result.errors.takeLast(MAX_ERRORS)
            result.errors.forEach { Log.w(TAG, it) }
            controller?.refresh()
        }
    }

    fun report(message: String) {
        Log.w(TAG, message)
        val cur = errors.value
        if (cur.lastOrNull() != message) errors.value = (cur + message).takeLast(MAX_ERRORS)
    }

    fun clearErrors() { errors.value = emptyList() }

    // --------------------------------------------------------------- settings

    fun updateSettings(transform: (GlyphSettings) -> GlyphSettings) {
        settings.value = transform(settings.value)
        controller?.refresh()
        persistJob?.cancel()
        persistJob = appScope.launch {
            delay(150)
            runCatching { repo.save(settings.value) }.onFailure { report("Could not save settings: ${it.message}") }
        }
    }

    /** UI visibility: the in-app matrix preview costs a little, so it only runs while the app is on screen. */
    @Synchronized
    fun setPreviewActive(active: Boolean) {
        // Reference-counted: the main screen and the quick-setup sheet can both be visible.
        previewUsers = if (active) previewUsers + 1 else (previewUsers - 1).coerceAtLeast(0)
        applySink()
    }

    /** Logo choice: stored in settings, launcher alias switched, notification icon refreshed by the service. */
    fun setIconStyle(style: IconStyle) {
        updateSettings { it.copy(iconStyle = style) }
        IconStyle.applyLauncher(app, style)
    }

    // ---------------------------------------------------------- owner lifecycle

    @Synchronized
    fun attach(context: Context, owner: Owner) {
        check(initialised) { "GlyphRuntime.init() must run in Application.onCreate" }
        val first = owners.isEmpty()
        owners += owner
        if (owner == Owner.SERVICE) serviceRunning.value = true
        if (owner == Owner.TOY) toyBound.value = true
        if (first) start(context.applicationContext)
        controller?.setToyMode(Owner.TOY in owners)
    }

    @Synchronized
    fun detach(owner: Owner) {
        if (!owners.remove(owner)) return
        if (owner == Owner.SERVICE) serviceRunning.value = false
        if (owner == Owner.TOY) toyBound.value = false
        if (owners.isEmpty()) stop() else controller?.setToyMode(Owner.TOY in owners)
    }

    private fun start(ctx: Context) {
        val n = matrixSize
        val r = GlyphRenderer(ctx, n) { report(it) }
        val e = GlyphAnimationEngine(engineScope, r, n) { report(it) }
        val c = GlyphController(
            engineScope, e, r, { settings.value }, { library.value },
            { state, name, dynamic ->
                activeState.value = state
                activeAnimation.value = name
                animating.value = dynamic
            }
        )
        renderer = r; engine = e; controller = c
        applySink()

        if (!linkHeld) {
            GlyphLink.acquire(ctx)
            linkHeld = true
        }

        volumeMonitor = VolumeMonitor(ctx) { pct, prev -> c.onVolumeChanged(pct, prev) }.also { it.start() }
        chargingMonitor = ChargingMonitor(ctx) { info -> c.onChargeChanged(info) }.also { it.start() }
        screenMonitor = ScreenMonitor(ctx) { on -> screenOn.value = on; c.onScreenChanged(on) }.also { it.start() }
        c.refresh()
    }

    private fun stop() {
        reconnectJob?.cancel()
        volumeMonitor?.stop(); volumeMonitor = null
        chargingMonitor?.stop(); chargingMonitor = null
        screenMonitor?.stop(); screenMonitor = null
        controller?.shutdown()
        // shutdown() runs on the engine thread; release the link after it has had a chance to clear the matrix.
        // If an owner re-attaches within that window, the link is simply kept (linkHeld stays true).
        appScope.launch {
            delay(300)
            synchronized(this@GlyphRuntime) {
                if (owners.isEmpty() && linkHeld) {
                    GlyphLink.release()
                    linkHeld = false
                }
            }
        }
        controller = null; renderer = null; engine = null
        activeState.value = GlyphState.IDLE
        activeAnimation.value = ""
        animating.value = false
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = appScope.launch {
            var attempt = 0
            while (attempt < MAX_RECONNECTS && GlyphLink.status.value != GlyphLink.Status.CONNECTED) {
                delay(2_000L * (attempt + 1))
                synchronized(this@GlyphRuntime) {
                    if (owners.isEmpty()) return@launch
                }
                GlyphLink.reconnect()
                attempt++
            }
            if (GlyphLink.status.value != GlyphLink.Status.CONNECTED) {
                report("Lost the Glyph service and could not reconnect. Toggle the controller off and on.")
            }
        }
    }

    private const val MAX_ERRORS = 25
    private const val MAX_RECONNECTS = 5
}

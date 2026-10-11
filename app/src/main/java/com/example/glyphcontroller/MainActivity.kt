package com.example.glyphcontroller

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val s by GlyphRuntime.settings.collectAsStateWithLifecycle()
            GlyphTheme(s.theme) { GlyphRoot() }
        }
    }

    override fun onStart() {
        super.onStart()
        GlyphRuntime.setPreviewActive(true)
    }

    override fun onStop() {
        GlyphRuntime.setPreviewActive(false)
        super.onStop()
    }
}

private enum class Tab(val label: String, val icon: List<String>) {
    HOME("HOME", listOf(".###.", "#...#", "#.#.#", "#...#", ".###.")),
    AOD("AOD", listOf("..#..", ".###.", "#####", ".###.", "..#..")),
    VOLUME("VOLUME", listOf("....#", "..#.#", "#.#.#", "#.#.#", "#.#.#")),
    CHARGE("CHARGE", listOf("...#.", "..#..", ".###.", "..#..", ".#...")),
    SETUP("SETUP", listOf(".#.#.", "#####", ".#.#.", "#####", ".#.#."))
}

private fun stateTitle(s: GlyphState) = when (s) {
    GlyphState.IDLE -> "AOD"
    GlyphState.VOLUME -> "VOLUME"
    GlyphState.CHARGING -> "CHARGING"
    GlyphState.NOTIFICATION -> "NOTIFICATION"
}

@Composable
private fun GlyphRoot() {
    val context = LocalContext.current
    val settings by GlyphRuntime.settings.collectAsStateWithLifecycle()
    val loaded by GlyphRuntime.settingsLoaded.collectAsStateWithLifecycle()
    val library by GlyphRuntime.library.collectAsStateWithLifecycle()
    val errors by GlyphRuntime.errors.collectAsStateWithLifecycle()
    val link by GlyphRuntime.linkStatus.collectAsStateWithLifecycle()
    val linkDetail by GlyphRuntime.linkDetail.collectAsStateWithLifecycle()
    val state by GlyphRuntime.activeState.collectAsStateWithLifecycle()
    val animName by GlyphRuntime.activeAnimation.collectAsStateWithLifecycle()
    // Not read here: reading it in this scope would recompose the whole screen on every matrix frame.
    val preview = GlyphRuntime.preview.collectAsStateWithLifecycle()
    val running by GlyphRuntime.serviceRunning.collectAsStateWithLifecycle()
    val toyBound by GlyphRuntime.toyBound.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var hint by remember { mutableStateOf<String?>(null) }
    var widgetHint by remember { mutableStateOf<String?>(null) }
    val scrolls = Tab.entries.map { rememberScrollState() }

    fun update(t: (GlyphSettings) -> GlyphSettings) = GlyphRuntime.updateSettings(t)
    fun withController(block: (GlyphController) -> Unit) {
        val c = GlyphRuntime.controller
        if (c == null) hint = "Turn the controller on first."
        else { hint = null; block(c) }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val err = JsonAnimationLoader.importFile(context, uri)
            if (err != null) GlyphRuntime.report(err) else GlyphRuntime.reloadLibrary()
        }
    }

    // If the switch is on but the service is not running (app was killed, device rebooted...), bring it back.
    LaunchedEffect(loaded, settings.enabled, running) {
        if (loaded && settings.enabled && !running) GlyphService.start(context)
    }

    val n = GlyphRuntime.matrixSize
    val shownLabel = library[animName]?.label ?: animName

    Column(Modifier.fillMaxSize().background(Pal.bg).safeDrawingPadding()) {
        // ------------------------------------------------------------ top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("GLYPH", color = Pal.text, fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                Text(
                    if (!settings.enabled) "OFF" else if (animName.isEmpty()) "READY" else "${stateTitle(state)} · $shownLabel",
                    color = Pal.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            MatrixPreview(preview, n, Modifier.size(46.dp))
        }

        // ------------------------------------------------------------ page
        Box(Modifier.weight(1f)) {
            Crossfade(targetState = tab, animationSpec = tween(160), label = "tab") { t ->
                TabPage(scrolls[t.ordinal]) {
                    when (t) {
                        Tab.HOME -> {
                            Hero(
                                headline = if (!settings.enabled) "OFF" else if (animName.isEmpty()) "READY" else stateTitle(state),
                                sub = if (animName.isEmpty()) "Glyph Matrix idle" else shownLabel,
                                active = settings.enabled && running, sdk = link == GlyphLink.Status.CONNECTED,
                                running = running, toy = toyBound, preview = preview, n = n
                            )
                            if (link != GlyphLink.Status.CONNECTED && linkDetail.isNotBlank()) {
                                Text("SDK ${link.name}: $linkDetail", color = Pal.dim, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                            Panel("CONTROL") {
                                ToggleRow("Dotwave", "Master switch", settings.enabled) { on ->
                                    update { it.copy(enabled = on) }
                                    if (on) {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                            != PackageManager.PERMISSION_GRANTED
                                        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        GlyphService.start(context)
                                    } else GlyphService.stop(context)
                                }
                                IntSetting("Global Glyph Brightness", settings.globalBrightness, 0..100, "%") { v ->
                                    update { it.copy(globalBrightness = v) }
                                }
                                Small("Scales the matrix output only. The phone screen brightness is never changed.")
                                Dropdown("Priority", PriorityPreset.entries.map { it to it.label }, settings.priority) { p ->
                                    update { it.copy(priority = p) }
                                }
                                Small("Idle (AOD) is always lowest. Default lets volume interrupt charging, then charging resumes.")
                            }
                            Panel("EVENTS") {
                                EventRow("VOLUME", library[settings.volumeAnimation]?.label ?: settings.volumeAnimation) {
                                    withController { it.simulateVolume(65) }
                                }
                                Divider()
                                EventRow("CHARGING", library[settings.chargingAnimation]?.label ?: settings.chargingAnimation) {
                                    withController { it.simulateCharging(ChargeKind.NORMAL, 62) }
                                }
                                Divider()
                                EventRow("NOTIFICATION", library[settings.notificationAnimation]?.label ?: settings.notificationAnimation) {
                                    withController { it.onNotification() }
                                }
                                hint?.let { Small(it) }
                                Small("TEST plays the event on the real matrix (the controller must be on).")
                            }
                            if (errors.isNotEmpty()) {
                                Panel("PROBLEMS", borderColor = Pal.accent, trailing = {
                                    TextButton(onClick = { GlyphRuntime.clearErrors() }) { Text("Clear", color = Pal.dim) }
                                }) {
                                    errors.forEach {
                                        Text(it, color = Color(0xFFFF8A8F), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }

                        Tab.AOD -> {
                            Panel("AOD") {
                                GlyphPicker("Animation", library, "aod", settings.aodAnimation, 0.5f) { name ->
                                    update { it.copy(aodAnimation = name) }
                                }
                            }
                            Panel("LOOK") {
                                FloatSetting("AOD Speed", settings.aodSpeed, 0.25f..3f) { v -> update { it.copy(aodSpeed = v) } }
                                IntSetting("AOD Brightness", settings.aodBrightness, 0..100, "%") { v ->
                                    update { it.copy(aodBrightness = v) }
                                }
                                ToggleRow("Only when screen is off", null, settings.aodOnlyWhenScreenOff) { on ->
                                    update { it.copy(aodOnlyWhenScreenOff = on) }
                                }
                            }
                            Panel("MUSIC") {
                                ToggleRow("Show playing music", "Only while a song plays; otherwise your AOD above", settings.musicAodEnabled) { on ->
                                    update { it.copy(musicAodEnabled = on) }
                                    if (on) GlyphRuntime.refreshMusic()
                                }
                                if (settings.musicAodEnabled) {
                                    Small("PATTERN")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MusicStyle.entries.forEach { st ->
                                            GChip(st.label.uppercase(), settings.musicStyle == st) {
                                                update { it.copy(musicStyle = st) }
                                            }
                                        }
                                    }
                                    ToggleRow("Show song name", "Off = pattern only, no scrolling name", settings.musicShowName) { on ->
                                        update { it.copy(musicShowName = on) }
                                    }
                                    if (settings.musicShowName) {
                                        IntSetting("Name scrolls every", settings.musicNameEverySec, 5..60, " s") { v ->
                                            update { it.copy(musicNameEverySec = v) }
                                        }
                                        Small("The name scrolls once, then the pattern plays for this long, then it repeats.")
                                    }
                                    OutlinedButton(onClick = {
                                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                    }) { Text("Grant notification access") }
                                    Small("Android only lets apps read the playing song through Notification access.")
                                }
                            }
                        }

                        Tab.VOLUME -> {
                            Panel("VOLUME") {
                                GlyphPicker("Animation", library, "volume", settings.volumeAnimation, 0.65f,
                                    sweep = true, topVariant = "max") { name ->
                                    update { it.copy(volumeAnimation = name) }
                                }
                            }
                            Panel("TIMING & LOOK") {
                                FloatSetting("Animation Duration", settings.volumeDurationMs / 1000f, 0.5f..10f, " s",
                                    decimals = 1, steps = 18) { v -> update { it.copy(volumeDurationMs = (v * 1000).roundToInt()) } }
                                FloatSetting("Volume Animation Speed", settings.volumeSpeed, 0.25f..3f) { v ->
                                    update { it.copy(volumeSpeed = v) }
                                }
                                IntSetting("Volume Brightness", settings.volumeBrightness, 0..100, "%") { v ->
                                    update { it.copy(volumeBrightness = v) }
                                }
                            }
                            Panel("TEST") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (p in listOf(0, 25, 50, 75, 100)) {
                                        TestButton("$p%", Modifier.weight(1f)) { withController { it.simulateVolume(p) } }
                                    }
                                }
                                hint?.let { Small(it) }
                            }
                        }

                        Tab.CHARGE -> {
                            Panel("CHARGING") {
                                GlyphPicker("Animation", library, "charging", settings.chargingAnimation, 0.6f,
                                    sweep = true, topVariant = "full", sweepSec = 16f) { name ->
                                    update { it.copy(chargingAnimation = name) }
                                }
                                Small("\"Battery %\" looks show the real battery level as digits with an animated ring around them. " +
                                    "Drag the preview level to see them react.")
                            }
                            Panel("LOOK") {
                                FloatSetting("Charging Speed", settings.chargingSpeed, 0.25f..3f) { v ->
                                    update { it.copy(chargingSpeed = v) }
                                }
                                IntSetting("Charging Brightness", settings.chargingBrightness, 0..100, "%") { v ->
                                    update { it.copy(chargingBrightness = v) }
                                }
                                Small("Fast / fully-charged looks come from each animation's \"variants\" in the JSON.")
                            }
                            Panel("TEST") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (k in ChargeKind.entries) {
                                        TestButton(k.name, Modifier.weight(1f)) {
                                            withController { it.simulateCharging(k, if (k == ChargeKind.FULL) 100 else 62) }
                                        }
                                    }
                                }
                                hint?.let { Small(it) }
                            }
                        }

                        Tab.SETUP -> {
                            Panel("THEME") {
                                ThemePicker(settings.theme) { th -> update { it.copy(theme = th) } }
                                Small("Colours only. The Glyph Matrix itself is always white LEDs.")
                            }
                            Panel("NOTIFICATION (optional)") {
                                ToggleRow("Notification flash", "Needs Notification access", settings.notificationEnabled) { on ->
                                    update { it.copy(notificationEnabled = on) }
                                }
                                GlyphPicker("Animation", library, "notification", settings.notificationAnimation, 0.5f,
                                    compact = true) { name -> update { it.copy(notificationAnimation = name) } }
                                FloatSetting("Duration", settings.notificationDurationMs / 1000f, 0.5f..10f, " s",
                                    decimals = 1, steps = 18) { v -> update { it.copy(notificationDurationMs = (v * 1000).roundToInt()) } }
                                IntSetting("Brightness", settings.notificationBrightness, 0..100, "%") { v ->
                                    update { it.copy(notificationBrightness = v) }
                                }
                                OutlinedButton(onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                }) { Text("Grant notification access") }
                            }
                            Panel("ICON & WIDGET") {
                                Small("LOGO + NOTIFICATION ICON")
                                IconStylePicker(settings.iconStyle) { GlyphRuntime.setIconStyle(it) }
                                Small("Switches the launcher icon and the status-bar icon together. Some launchers need a few seconds or a restart.")
                                OutlinedButton(onClick = { widgetHint = pinWidget(context) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Add home-screen widget", fontSize = 12.sp)
                                }
                                Small("Shows the current AOD as a dot-matrix picture (about 2 updates per second). Tap it for quick setup.")
                                widgetHint?.let { Small(it) }
                            }
                            Panel("ANIMATION FILES") {
                                Small("${library.animations.size} loaded. Your own JSON: ${JsonAnimationLoader.userDir(context).absolutePath}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                                        Text("Import JSON", fontSize = 12.sp)
                                    }
                                    OutlinedButton(onClick = { GlyphRuntime.reloadLibrary() }, modifier = Modifier.weight(1f)) {
                                        Text("Reload", fontSize = 12.sp)
                                    }
                                }
                                OutlinedButton(onClick = { openToysManager(context) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open Glyph Toys manager (enable the always-on toy)", fontSize = 12.sp)
                                }
                            }
                            Panel("ADVANCED") {
                                IntSetting("Keep-alive resend", settings.keepAliveSec, 0..60, " s") { v ->
                                    update { it.copy(keepAliveSec = v) }
                                }
                                Small("Re-sends a static picture this often (0 = never). Whether this prevents the system's matrix timeout is unverified.")
                                ToggleRow("Animate with screen off", "Holds a wake lock - uses battery", settings.animateWhenScreenOff) { on ->
                                    update { it.copy(animateWhenScreenOff = on) }
                                }
                                Dropdown("setGlyphMatrixTimeout (undocumented)",
                                    MatrixTimeoutCall.entries.map { it to it.label }, settings.matrixTimeoutCall) { m ->
                                    update { it.copy(matrixTimeoutCall = m) }
                                }
                            }
                            Panel("ABOUT") {
                                Text("Dotwave", color = Pal.text, fontSize = 15.sp)
                                Small("Vibe-coded: built by describing ideas to an AI assistant (Claude) and iterating. " +
                                    "It is hobby software, so expect rough edges.")
                                Small("Free and open source (MIT). Uses Nothing's Glyph Matrix SDK under Nothing's own licence. " +
                                    "Not affiliated with or endorsed by Nothing.")
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ bottom bar
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.border))
        Row(
            Modifier.fillMaxWidth().background(Pal.surface).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Tab.entries.forEach { t ->
                val sel = t == tab
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { tab = t }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    DotIcon(t.icon, if (sel) Pal.accent else Pal.dim, 22.dp)
                    Text(t.label, color = if (sel) Pal.text else Pal.dim, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun TabPage(scroll: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun Hero(
    headline: String, sub: String, active: Boolean, sdk: Boolean, running: Boolean, toy: Boolean,
    preview: State<IntArray>, n: Int
) {
    val shape = RoundedCornerShape(30.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Pal.surface, Pal.bg)))
            .border(BorderStroke(1.dp, Pal.border), shape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(212.dp)
                .clip(CircleShape)
                .background(Pal.bg)
                .border(BorderStroke(1.5.dp, if (active) Pal.accent else Pal.border), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            MatrixPreview(preview, n, Modifier.size(184.dp))
        }
        Text(headline, color = Pal.text, fontSize = 24.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Text(sub, color = Pal.dim, fontSize = 13.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("SDK", sdk)
            StatusPill("SERVICE", running)
            if (toy) StatusPill("TOY", true)
        }
    }
}

@Composable
private fun EventRow(name: String, animLabel: String, onTest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = Pal.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
            Text(animLabel, color = Pal.text, fontSize = 14.sp, maxLines = 1)
        }
        TestButton("TEST", Modifier.width(84.dp), onClick = onTest)
    }
}

@Composable
private fun TestButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Pal.border), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
    ) { Text(text.lowercase(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pal.text) }
}

@Composable
private fun Small(text: String) {
    Text(text, color = Pal.dim, fontSize = 11.sp, lineHeight = 15.sp)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.border))
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.text, fontSize = 15.sp)
            if (subtitle != null) Small(subtitle)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Pal.onAccent, checkedTrackColor = Pal.accent,
                uncheckedThumbColor = Pal.dim, uncheckedTrackColor = Pal.border, uncheckedBorderColor = Pal.border
            )
        )
    }
}

@Composable
private fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: selected?.toString() ?: "-"
    Column {
        Text(label, color = Pal.text, fontSize = 14.sp)
        Box {
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, Pal.border), RoundedCornerShape(12.dp))
                    .clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(shown, color = Pal.text, fontSize = 14.sp)
                Text("▾", color = Pal.dim)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
                }
            }
        }
    }
}

@Composable
private fun MatrixPreview(pixelState: State<IntArray>, n: Int, modifier: Modifier = Modifier) {
    val on = Pal.dot
    val off = Pal.dotOff
    Canvas(modifier.aspectRatio(1f)) {
        val pixels = pixelState.value   // read in the draw phase: only this canvas is redrawn per frame
        val cell = size.minDimension / n
        val r = cell * 0.38f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val c = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                drawCircle(off, radius = r * 0.75f, center = c)
                val v = pixels.getOrElse(y * n + x) { 0 }
                // gamma: LEDs at 40% brightness still look clearly lit, a linear alpha made the preview look dead
                if (v > 0) drawCircle(on.copy(alpha = (v / 255f).pow(0.55f).coerceIn(0.12f, 1f)), radius = r, center = c)
            }
        }
    }
}

private fun pinWidget(context: Context): String? {
    val mgr = android.appwidget.AppWidgetManager.getInstance(context)
    return if (mgr.isRequestPinAppWidgetSupported) {
        mgr.requestPinAppWidget(ComponentName(context, GlyphWidgetProvider::class.java), null, null)
        null
    } else "This launcher cannot pin widgets from an app. Long-press the home screen > Widgets > Glyph."
}

private fun openToysManager(context: Context) {
    try {
        context.startActivity(
            Intent().setComponent(
                ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        GlyphRuntime.report(
            "Could not open the Glyph Toys manager (Nothing documents it for system versions from 2025-08-29). " +
                "Use Settings > Glyph Interface > Flip to Glyph > Always-on Glyph Toy instead."
        )
    }
}

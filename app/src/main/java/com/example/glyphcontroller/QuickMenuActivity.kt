package com.example.glyphcontroller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.pow
import kotlin.math.roundToInt

private val Scrim = Color(0xAA000000)

/**
 * Bottom sheet opened by tapping the home-screen widget: the live matrix, master switch, brightness,
 * AOD animation, speed and the logo / notification-icon picker. Everything goes through [GlyphRuntime],
 * so it stays in sync with the main screen.
 */
class QuickMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val st by GlyphRuntime.settings.collectAsStateWithLifecycle()
            GlyphTheme(st.theme, systemBars = false) {
                QuickMenu(
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
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

@Composable
private fun QuickMenu(onClose: () -> Unit, onOpenApp: () -> Unit) {
    val context = LocalContext.current
    val s by GlyphRuntime.settings.collectAsStateWithLifecycle()
    val library by GlyphRuntime.library.collectAsStateWithLifecycle()
    val preview = GlyphRuntime.preview.collectAsStateWithLifecycle()
    val state by GlyphRuntime.activeState.collectAsStateWithLifecycle()
    val running by GlyphRuntime.serviceRunning.collectAsStateWithLifecycle()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val aodList = library.byRole("aod")
    val current = library[s.aodAnimation]

    val noRipple = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim)
            .clickable(interactionSource = noRipple, indication = null) { onClose() }
            .safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Pal.surface)
                .border(BorderStroke(1.dp, Pal.border), RoundedCornerShape(28.dp))
                // swallow taps so only the scrim closes the sheet
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ------------------------------------------------------------ header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QuickMatrix(preview, GlyphRuntime.matrixSize, Modifier.size(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("QUICK SETUP", Pal.accent)
                    Text(current?.label ?: s.aodAnimation, color = Pal.text, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2)
                    Mono(
                        when {
                            !s.enabled -> "○ OFF"
                            !running -> "○ STARTING"
                            state == GlyphState.IDLE -> "● AOD"
                            else -> "● ${state.name}"
                        }
                    )
                }
                TextButton(onClick = onClose) { Text("✕", color = Pal.dim, fontSize = 18.sp) }
            }

            // ------------------------------------------------------------ master
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glyphic", color = Pal.text, fontSize = 15.sp)
                    Text("Master switch", color = Pal.dim, fontSize = 11.sp)
                }
                Switch(
                    checked = s.enabled,
                    onCheckedChange = { on ->
                        GlyphRuntime.updateSettings { it.copy(enabled = on) }
                        if (on) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            GlyphService.start(context)
                        } else GlyphService.stop(context)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Pal.onAccent, checkedTrackColor = Pal.accent,
                        uncheckedThumbColor = Pal.dim, uncheckedTrackColor = Pal.border,
                        uncheckedBorderColor = Pal.border
                    )
                )
            }

            Rule()

            // ------------------------------------------------------------ AOD picker
            GlyphPicker("AOD animation", library, "aod", s.aodAnimation, 0.5f, compact = true) { name ->
                GlyphRuntime.updateSettings { it.copy(aodAnimation = name) }
            }
            OutlinedButton(
                onClick = {
                    val others = aodList.filter { it.name != s.aodAnimation }
                    if (others.isNotEmpty()) {
                        val name = others.random().name
                        GlyphRuntime.updateSettings { it.copy(aodAnimation = name) }
                    }
                },
                enabled = aodList.size > 1, modifier = Modifier.fillMaxWidth()
            ) { Text("Shuffle", fontSize = 12.sp) }

            QSlider("Glyph brightness", s.globalBrightness.toFloat(), 0f..100f, "${s.globalBrightness}%") { v ->
                GlyphRuntime.updateSettings { it.copy(globalBrightness = v.roundToInt()) }
            }
            QSlider("AOD speed", s.aodSpeed, 0.25f..3f, "${"%.2f".format(s.aodSpeed)}×") { v ->
                GlyphRuntime.updateSettings { it.copy(aodSpeed = v) }
            }

            Rule()

            // ------------------------------------------------------------ icon picker
            Mono("LOGO & NOTIFICATION ICON")
            IconStylePicker(s.iconStyle) { GlyphRuntime.setIconStyle(it) }
            Text(
                "Changes the launcher icon and the status-bar icon. Some launchers take a few seconds (or a restart) to show the new launcher icon.",
                color = Pal.dim, fontSize = 10.sp, lineHeight = 14.sp
            )

            OutlinedButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                Text("Open full app", fontSize = 12.sp)
            }
        }
    }
}

/** Three selectable logos; the same artwork is used for the launcher icon and the status-bar icon. */
@Composable
internal fun IconStylePicker(selected: IconStyle, onSelect: (IconStyle) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (style in IconStyle.entries) {
            val isSel = style == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Pal.bg)
                    .border(BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) Pal.accent else Pal.border), RoundedCornerShape(16.dp))
                    .clickable { onSelect(style) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Image(painterResource(style.notif), contentDescription = style.label, modifier = Modifier.size(40.dp))
                Mono(style.label.uppercase(), if (isSel) Pal.text else Pal.dim)
            }
        }
    }
}

@Composable
private fun QSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String,
                    onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Pal.text, fontSize = 14.sp)
            Text(shown, color = Pal.dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Pal.text, activeTrackColor = Pal.accent, inactiveTrackColor = Pal.border)
        )
    }
}

@Composable
private fun Mono(text: String, color: Color = Pal.dim) {
    Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium)
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.border))
}

@Composable
private fun QuickMatrix(pixelState: State<IntArray>, n: Int, modifier: Modifier = Modifier) {
    val on = Pal.dot
    val off = Pal.dotOff
    Canvas(modifier.aspectRatio(1f)) {
        val pixels = pixelState.value
        val cell = size.minDimension / n
        val r = cell * 0.38f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val c = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                drawCircle(off, radius = r * 0.75f, center = c)
                val v = pixels.getOrElse(y * n + x) { 0 }
                if (v > 0) drawCircle(on.copy(alpha = (v / 255f).pow(0.55f).coerceIn(0.12f, 1f)), radius = r, center = c)
            }
        }
    }
}

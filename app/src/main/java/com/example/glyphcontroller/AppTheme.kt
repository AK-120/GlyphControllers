package com.example.glyphcontroller

import android.app.Activity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** App colour themes (selectable in Settings). Only the colours differ; layout is identical. */
enum class AppTheme(
    val label: String,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val dim: Color,
    /** lit LED colour */
    val dot: Color,
    /** unlit LED colour */
    val dotOff: Color
) {
    NOTHING("Nothing", true, c(0x000000), c(0x0E0E0E), c(0x2A2A2A), c(0xD71921), c(0xFFFFFF), c(0xFFFFFF), c(0x8C8C8C), c(0xFFFFFF), c(0x1C1C1C)),
    PAPER("Paper", false, c(0xF4F1EA), c(0xFFFFFF), c(0xD9D4C7), c(0xD71921), c(0xFFFFFF), c(0x111111), c(0x6F6B62), c(0x111111), c(0xE3DED2)),
    CYBER("Cyber", true, c(0x04070D), c(0x0A111C), c(0x16324A), c(0x00E5FF), c(0x000000), c(0xE6FBFF), c(0x6D8FA3), c(0x7DF3FF), c(0x10202F)),
    AMBER("Amber", true, c(0x080500), c(0x130C00), c(0x3A2800), c(0xFFB000), c(0x000000), c(0xFFD98A), c(0x9A7424), c(0xFFB000), c(0x241800)),
    MINT("Mint", true, c(0x040A07), c(0x0A140F), c(0x18382A), c(0x3DFFA2), c(0x000000), c(0xE8FFF4), c(0x6FA38A), c(0x9CFFD0), c(0x102219)),
    VIOLET("Violet", true, c(0x08050F), c(0x120C1F), c(0x2E2150), c(0xB388FF), c(0x000000), c(0xF1E9FF), c(0x8E7FB0), c(0xD4BBFF), c(0x1C1431)),
    MONO("Mono", true, c(0x000000), c(0x111111), c(0x333333), c(0xFFFFFF), c(0x000000), c(0xFFFFFF), c(0x888888), c(0xFFFFFF), c(0x1E1E1E)),
    SUNSET("Sunset", true, c(0x0D0507), c(0x170A0E), c(0x3A1B24), c(0xFF6B4A), c(0x000000), c(0xFFE7DC), c(0xA37A70), c(0xFFB199), c(0x2A1418)),
    OCEAN("Ocean", true, c(0x020A14), c(0x07131F), c(0x12314A), c(0x2F9BFF), c(0x000000), c(0xDCEEFF), c(0x6C8CA8), c(0x8CC8FF), c(0x0E2133)),
    TERMINAL("Terminal", true, c(0x000800), c(0x041004), c(0x0F3A0F), c(0x33FF33), c(0x000000), c(0xB6FFB6), c(0x4F8F4F), c(0x33FF33), c(0x0A200A)),
    ROSE("Rose", true, c(0x0E0509), c(0x1A0A11), c(0x42182A), c(0xFF4F9A), c(0x000000), c(0xFFE3EE), c(0xA3708A), c(0xFFA3CB), c(0x2B1220)),
    SOLAR("Solar", true, c(0x070800), c(0x101200), c(0x2F3300), c(0xE8FF3A), c(0x000000), c(0xF6FFB0), c(0x8C9444), c(0xE8FF3A), c(0x1B1E00)),
    DUSK("Dusk", true, c(0x0A0B14), c(0x12131F), c(0x2A2C47), c(0xFF9E64), c(0x000000), c(0xE8E9FF), c(0x7F82A8), c(0xC0C4FF), c(0x1B1D30)),
    ICE("Ice", false, c(0xEAF3FA), c(0xFFFFFF), c(0xC5D6E4), c(0x0A6CFF), c(0xFFFFFF), c(0x0B1B2B), c(0x5F7488), c(0x0B1B2B), c(0xD3E1EC)),
    SAKURA("Sakura", false, c(0xFBF1F4), c(0xFFFFFF), c(0xEBCFD8), c(0xE0457B), c(0xFFFFFF), c(0x2A1018), c(0x8C6672), c(0x2A1018), c(0xF0DDE3)),
    MOSS("Moss", false, c(0xEEF2E8), c(0xFAFCF6), c(0xCDD6BF), c(0x2F7D32), c(0xFFFFFF), c(0x11190F), c(0x66705E), c(0x11190F), c(0xDCE3D0));
}

private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

val LocalAppTheme = staticCompositionLocalOf { AppTheme.NOTHING }

/** Composable accessors so screens write `Pal.accent` instead of passing colours around. */
object Pal {
    val bg: Color @Composable get() = LocalAppTheme.current.bg
    val surface: Color @Composable get() = LocalAppTheme.current.surface
    val border: Color @Composable get() = LocalAppTheme.current.border
    val accent: Color @Composable get() = LocalAppTheme.current.accent
    val onAccent: Color @Composable get() = LocalAppTheme.current.onAccent
    val text: Color @Composable get() = LocalAppTheme.current.text
    val dim: Color @Composable get() = LocalAppTheme.current.dim
    val dot: Color @Composable get() = LocalAppTheme.current.dot
    val dotOff: Color @Composable get() = LocalAppTheme.current.dotOff
}

@Composable
fun GlyphTheme(theme: AppTheme, systemBars: Boolean = true, content: @Composable () -> Unit) {
    val scheme = if (theme.dark) {
        darkColorScheme(
            background = theme.bg, surface = theme.surface, surfaceContainer = theme.surface,
            primary = theme.accent, onPrimary = theme.onAccent, onBackground = theme.text, onSurface = theme.text,
            outline = theme.border, outlineVariant = theme.border
        )
    } else {
        lightColorScheme(
            background = theme.bg, surface = theme.surface, surfaceContainer = theme.surface,
            primary = theme.accent, onPrimary = theme.onAccent, onBackground = theme.text, onSurface = theme.text,
            outline = theme.border, outlineVariant = theme.border
        )
    }
    if (systemBars) {
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = theme.bg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = theme.bg.toArgb()
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = !theme.dark
            ctrl.isAppearanceLightNavigationBars = !theme.dark
        }
    }
    CompositionLocalProvider(LocalAppTheme provides theme, LocalContentColor provides theme.text) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

package com.example.glyphcontroller

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared building blocks for the redesigned screens (kept internal so every screen can use them). */

/** Rounded panel with a small accent dot + mono title. */
@Composable
internal fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    borderColor: Color = Pal.border,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Pal.surface)
            .border(BorderStroke(1.dp, borderColor), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Pal.accent))
            Spacer(Modifier.width(10.dp))
            Text(
                title, color = Pal.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        content()
    }
}

/** Pill button / filter chip. */
@Composable
internal fun GChip(text: String, selected: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (danger) Color(0xFFFF8A8F) else if (selected) Pal.onAccent else Pal.dim, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Pal.accent else Color.Transparent)
            .border(BorderStroke(1.dp, if (danger) Color(0x66FF8A8F) else if (selected) Pal.accent else Pal.border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** Small read-only value badge (right side of a slider label). */
@Composable
internal fun ValueTag(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text, color = Pal.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(shape)
            .background(Pal.bg)
            .border(BorderStroke(1.dp, Pal.border), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** Dot-matrix slider: a row of LEDs that fill up. Tap or drag. [value] is 0..1. */
@Composable
internal fun DotSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, dots: Int = 26) {
    val latest = rememberUpdatedState(onChange)
    val on = Pal.accent
    val off = Pal.border
    val thumb = Pal.text
    val v = value.coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val gap = size.width / dots
                    latest.value(((o.x - gap / 2f) / (size.width - gap)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val gap = size.width / dots
                        latest.value(((o.x - gap / 2f) / (size.width - gap)).coerceIn(0f, 1f))
                    }
                ) { change, _ ->
                    change.consume()
                    val gap = size.width / dots
                    latest.value(((change.position.x - gap / 2f) / (size.width - gap)).coerceIn(0f, 1f))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val gap = w / dots
        val r = min(gap * 0.30f, h * 0.17f)
        val tx = gap / 2f + v * (w - gap)
        for (i in 0 until dots) {
            val cx = (i + 0.5f) * gap
            val lit = cx <= tx + 0.5f
            drawCircle(if (lit) on else off, radius = if (lit) r else r * 0.7f, center = Offset(cx, h / 2f))
        }
        drawCircle(thumb, radius = h * 0.30f, center = Offset(tx, h / 2f))
        drawCircle(on, radius = h * 0.13f, center = Offset(tx, h / 2f))
    }
}

@Composable
internal fun IntSetting(label: String, value: Int, range: IntRange, suffix: String, onChange: (Int) -> Unit) {
    val span = (range.last - range.first).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ValueTag("$value$suffix")
        }
        DotSlider((value - range.first).toFloat() / span, { f -> onChange(range.first + (f * span).roundToInt()) })
    }
}

/** [steps] = number of snap points between min and max (0 = continuous). */
@Composable
internal fun FloatSetting(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String = "×",
    decimals: Int = 2, steps: Int = 0, onChange: (Float) -> Unit
) {
    val span = range.endInclusive - range.start
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ValueTag("${"%.${decimals}f".format(value)}$suffix")
        }
        DotSlider((value - range.start) / span, { f ->
            var q = f
            if (steps > 0) q = (f * (steps + 1)).roundToInt() / (steps + 1f)
            onChange(range.start + q * span)
        })
    }
}

/** Tiny dot-matrix icon from a list of equal-length strings ('#' = lit). */
@Composable
internal fun DotIcon(rows: List<String>, color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val n = rows.size
        val cell = size.minDimension / n
        for (y in 0 until n) {
            for (x in 0 until rows[y].length) {
                if (rows[y][x] == '#') drawCircle(color, radius = cell * 0.37f, center = Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
            }
        }
    }
}

@Composable
internal fun StatusPill(label: String, ok: Boolean) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .border(BorderStroke(1.dp, Pal.border), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) Pal.accent else Pal.dim))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (ok) Pal.text else Pal.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

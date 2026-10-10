package com.example.glyphcontroller

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlinx.coroutines.delay

/**
 * Samples one picture of an animation at a point in time, only for the picker previews.
 * It mirrors what the engine does (frames: step by duration; procedural: generator.render), minus
 * brightness scaling and the SDK.
 */
internal class ThumbSampler(private val a: GlyphAnimation, private val n: Int, private val input: Float) {
    private val gen: ProceduralGenerator? =
        (a as? ProceduralAnimation)?.let { ProceduralGenerators.create(it.generator, it.params, n) }
    private var lastT = -1f
    private var started = false

    fun render(tSec: Float, out: IntArray, inputNow: Float = input) {
        when (a) {
            is FramesAnimation -> {
                val total = a.steps.sumOf { it.durationMs }.coerceAtLeast(1L)
                var ms = ((tSec * 1000f).toLong()) % total
                var pick = a.steps.firstOrNull()
                for (s in a.steps) {
                    if (ms < s.durationMs) { pick = s; break }
                    ms -= s.durationMs
                }
                val px = pick?.pixels
                for (i in out.indices) out[i] = px?.getOrNull(i) ?: 0
            }
            is ProceduralAnimation -> {
                val g = gen ?: return
                if (!started) { g.start(inputNow); started = true }
                // non-looping effects (e.g. notification ripple) are replayed with a pause
                val span = if (a.loop) Float.MAX_VALUE else (a.durationMs / 1000f + 0.6f)
                val t = tSec % span
                val dt = if (lastT < 0f || t < lastT) 1f / 12f else t - lastT
                lastT = t
                g.render(t, dt.coerceIn(0f, 0.25f), inputNow, out)
            }
        }
    }
}

/**
 * One animation plus (optionally) its "100%" / "full" variant. [draw] with a level swaps to the variant at the
 * top of the range, like the controller does for real.
 */
internal class LiveThumb(a: GlyphAnimation, top: GlyphAnimation?, n: Int, input: Float) {
    private val main = ThumbSampler(a, n, input)
    private val topS = top?.let { ThumbSampler(it, n, 1f) }

    fun draw(tSec: Float, level: Float?, out: IntArray) {
        if (level == null) main.render(tSec, out)
        else if (level >= 0.995f && topS != null) topS.render(tSec, out, 1f)
        else main.render(tSec, out, level)
    }
}

/** Triangle sweep 0 -> 100 -> 0 with a short plateau at the top. */
internal fun sweepLevel(tSec: Float, periodSec: Float): Float {
    val ph = (tSec / periodSec) % 1f
    val tri = if (ph < 0.5f) ph * 2f else 2f - ph * 2f
    return (tri * 1.3f).coerceIn(0.03f, 1f)
}

private fun DrawScope.drawDots(px: IntArray, n: Int, on: Color, off: Color, glow: Boolean) {
    val cell = size.minDimension / n
    val r = cell * 0.40f
    for (y in 0 until n) {
        for (x in 0 until n) {
            val c = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
            drawCircle(off, radius = r * 0.7f, center = c)
            val v = px[y * n + x]
            if (v > 0) {
                val a = (v / 255f).pow(0.55f).coerceIn(0.12f, 1f)
                if (glow && v > 120) drawCircle(on.copy(alpha = a * 0.16f), radius = r * 1.8f, center = c)
                drawCircle(on.copy(alpha = a), radius = r, center = c)
            }
        }
    }
}

private fun familyOf(a: GlyphAnimation): String =
    if (" · " in a.label) a.label.substringBefore(" · ").trim() else "Other"

/**
 * Animation picker: a big live "stage" showing the selected animation (with a level scrubber for volume and
 * battery looks), family filter chips, prev / next / shuffle, and a strip of live tiles. Tap a tile to select.
 *
 * @param sampleInput value fed to level-style generators when not sweeping
 * @param sweep       preview the level 0..100..0 (volume, battery); the top of the sweep shows [topVariant]
 * @param compact     tile strip only (used in the small quick-setup sheet)
 */
@Composable
fun GlyphPicker(
    title: String,
    library: AnimationLibrary,
    role: String,
    selected: String,
    sampleInput: Float,
    sweep: Boolean = false,
    topVariant: String = "max",
    sweepSec: Float = 9f,
    compact: Boolean = false,
    onSelect: (String) -> Unit
) {
    val all = library.byRole(role)
    val n = GlyphRuntime.matrixSize
    val listState = rememberLazyListState()
    val clock = remember { mutableFloatStateOf(0f) }
    var family by rememberSaveable(role) { mutableStateOf("") }          // "" = all
    var manual by remember { mutableStateOf<Float?>(null) }

    val context = LocalContext.current
    // Only glyphs that came from an imported file can be deleted; bundled ones never show the button.
    val importedNames = remember(library) { JsonAnimationLoader.userAnimationNames(context) }
    var confirmDelete by remember { mutableStateOf<GlyphAnimation?>(null) }

    val families = remember(all) { all.map { familyOf(it) }.distinct() }
    val shown = if (family.isEmpty() || family !in families) all else all.filter { familyOf(it) == family }
    val index = all.indexOfFirst { it.name == selected }
    val current = all.getOrNull(index)

    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            clock.floatValue = (System.nanoTime() - start) / 1e9f
            delay(80)
        }
    }
    LaunchedEffect(selected, family, all.size) {
        val i = shown.indexOfFirst { it.name == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }

    fun step(d: Int) {
        val list = if (shown.isEmpty()) all else shown
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.name == selected }
        onSelect(list[(((if (i < 0) 0 else i) + d) % list.size + list.size) % list.size].name)
    }

    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${a.label}\"?") },
            text = { Text("This removes only this imported glyph. Built-in glyphs and your other imports are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    // move the selection to a neighbour first so no slot is left pointing at the deleted glyph
                    if (selected == a.name) {
                        val i = all.indexOfFirst { it.name == a.name }
                        all.getOrNull(i + 1)?.takeIf { it.name != a.name }?.let { onSelect(it.name) }
                            ?: all.getOrNull(i - 1)?.let { onSelect(it.name) }
                    }
                    val err = JsonAnimationLoader.deleteUserAnimation(context, a.name)
                    if (err != null) GlyphRuntime.report(err) else GlyphRuntime.reloadLibrary()
                }) { Text("Delete", color = Color(0xFFFF8A8F)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Pal.text, fontSize = 14.sp)
            Text(
                if (all.isEmpty()) "none" else "${if (index < 0) "?" else (index + 1).toString()} / ${all.size}",
                color = Pal.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
        }
        if (all.isEmpty()) {
            Text("No animations for this role were loaded.", color = Pal.dim, fontSize = 11.sp)
            return@Column
        }

        if (!compact && current != null) {
            Stage(
                current, library[current.variants[topVariant]].takeIf { sweep }, n, sweep, sweepSec, sampleInput,
                clock, manual, { manual = it },
                onPrev = { step(-1) }, onNext = { step(1) },
                onShuffle = { onSelect(all.random().name) },
                onDelete = if (current.name in importedNames) ({ confirmDelete = current }) else null
            )
        }

        if (!compact && families.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { GChip("ALL ${all.size}", family.isEmpty()) { family = "" } }
                itemsIndexed(families) { _, f ->
                    GChip(f.uppercase(), family == f) { family = if (family == f) "" else f }
                }
            }
        }

        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
        ) {
            itemsIndexed(shown, key = { _, a -> a.name }) { _, a ->
                GlyphTile(
                    a, library[a.variants[topVariant]].takeIf { sweep }, n, sampleInput, sweep, sweepSec, clock,
                    a.name == selected
                ) { onSelect(a.name) }
            }
        }
    }
}

@Composable
private fun Stage(
    a: GlyphAnimation, top: GlyphAnimation?, n: Int, sweep: Boolean, sweepSec: Float, input: Float,
    clock: State<Float>, manual: Float?, onManual: (Float?) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onShuffle: () -> Unit, onDelete: (() -> Unit)? = null
) {
    val live = remember(a.name, top?.name, n) { LiveThumb(a, top, n, input) }
    val px = remember(n) { IntArray(n * n) }
    val on = Pal.dot
    val off = Pal.dotOff
    val shape = RoundedCornerShape(24.dp)
    val meta = when (a) {
        is FramesAnimation -> "${a.steps.size} frames"
        is ProceduralAnimation -> "live · ${a.generator}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(148.dp).clip(shape).background(Pal.bg).border(BorderStroke(1.dp, Pal.border), shape)
                    .padding(10.dp)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val t = clock.value
                    live.draw(t, if (sweep) (manual ?: sweepLevel(t, sweepSec)) else null, px)
                    drawDots(px, n, on, off, true)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.label, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                Text(meta, color = Pal.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GChip("‹", false, onClick = onPrev)
                    GChip("›", false, onClick = onNext)
                    GChip("SHUFFLE", false, onClick = onShuffle)
                }
                if (onDelete != null) GChip("DELETE", false, danger = true, onClick = onDelete)
            }
        }
        if (sweep) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PREVIEW LEVEL", color = Pal.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                GChip(if (manual == null) "AUTO" else "${(manual * 100).toInt()}%  ·  AUTO?", manual == null) { onManual(null) }
            }
            DotSlider(manual ?: 0.5f, { onManual(it) }, dots = 30)
        }
    }
}

@Composable
private fun GlyphTile(
    a: GlyphAnimation, top: GlyphAnimation?, n: Int, input: Float, sweep: Boolean, sweepSec: Float,
    clock: State<Float>, isSelected: Boolean, onClick: () -> Unit
) {
    val live = remember(a.name, top?.name, n) { LiveThumb(a, top, n, input) }
    val pixels = remember(n) { IntArray(n * n) }
    val on = Pal.dot
    val off = Pal.dotOff
    val accent = Pal.accent
    val border = Pal.border
    Column(
        Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) accent else border), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(74.dp)) {
            // the clock is read here, so only this draw pass is redone on each tick
            val t = clock.value
            live.draw(t, if (sweep) sweepLevel(t, sweepSec) else null, pixels)
            drawDots(pixels, n, on, off, false)
        }
        Text(
            a.label.substringAfter(" · "), color = if (isSelected) Pal.text else Pal.dim, fontSize = 10.sp, lineHeight = 12.sp,
            maxLines = 2, textAlign = TextAlign.Center,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Grid of theme swatches; each shows its own colours with a small dot-matrix ring. */
@Composable
fun ThemePicker(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rowThemes in AppTheme.entries.chunked(4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (t in rowThemes) {
                    ThemeTile(t, t == selected, Modifier.weight(1f)) { onSelect(t) }
                }
                repeat(4 - rowThemes.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThemeTile(t: AppTheme, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(t.bg)
            .border(BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) t.accent else t.border), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(44.dp)) {
            val n = 9
            val cell = size.minDimension / n
            val ctr = (n - 1) / 2f
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val d = kotlin.math.hypot(x - ctr, y - ctr)
                    val c = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                    drawCircle(t.dotOff, radius = cell * 0.28f, center = c)
                    if (d in 2.9f..4.2f) drawCircle(t.dot, radius = cell * 0.38f, center = c)
                    if (x == 4 && y == 4) drawCircle(t.accent, radius = cell * 0.46f, center = c)
                }
            }
        }
        Text(t.label.uppercase(), color = t.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

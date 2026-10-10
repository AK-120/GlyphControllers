package com.example.glyphcontroller

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Picture shown between name scrolls (and alone when the name is off). */
enum class MusicStyle(val key: String, val label: String) {
    DISC("disc", "Disc"), BARS("bars", "Bars"), WAVE("wave", "Wave"), PULSE("pulse", "Pulse");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: DISC
    }
}

/** Tiny 3x5 pixel font used to scroll song names across the matrix. */
internal object PixelFont {
    private val glyphs: Map<Char, List<String>> = buildMap {
        fun g(c: Char, a: String, b: String, d: String, e: String, f: String) { put(c, listOf(a, b, d, e, f)) }
        g('A', ".#.", "#.#", "###", "#.#", "#.#"); g('B', "##.", "#.#", "##.", "#.#", "##.")
        g('C', ".##", "#..", "#..", "#..", ".##"); g('D', "##.", "#.#", "#.#", "#.#", "##.")
        g('E', "###", "#..", "##.", "#..", "###"); g('F', "###", "#..", "##.", "#..", "#..")
        g('G', ".##", "#..", "#.#", "#.#", ".##"); g('H', "#.#", "#.#", "###", "#.#", "#.#")
        g('I', "###", ".#.", ".#.", ".#.", "###"); g('J', "..#", "..#", "..#", "#.#", ".#.")
        g('K', "#.#", "#.#", "##.", "#.#", "#.#"); g('L', "#..", "#..", "#..", "#..", "###")
        g('M', "#.#", "###", "###", "#.#", "#.#"); g('N', "##.", "#.#", "#.#", "#.#", "#.#")
        g('O', ".#.", "#.#", "#.#", "#.#", ".#."); g('P', "##.", "#.#", "##.", "#..", "#..")
        g('Q', ".#.", "#.#", "#.#", "##.", ".##"); g('R', "##.", "#.#", "##.", "#.#", "#.#")
        g('S', ".##", "#..", ".#.", "..#", "##."); g('T', "###", ".#.", ".#.", ".#.", ".#.")
        g('U', "#.#", "#.#", "#.#", "#.#", "###"); g('V', "#.#", "#.#", "#.#", "#.#", ".#.")
        g('W', "#.#", "#.#", "###", "###", "#.#"); g('X', "#.#", "#.#", ".#.", "#.#", "#.#")
        g('Y', "#.#", "#.#", ".#.", ".#.", ".#."); g('Z', "###", "..#", ".#.", "#..", "###")
        g('0', "###", "#.#", "#.#", "#.#", "###"); g('1', ".#.", "##.", ".#.", ".#.", "###")
        g('2', "##.", "..#", ".#.", "#..", "###"); g('3', "##.", "..#", ".#.", "..#", "##.")
        g('4', "#.#", "#.#", "###", "..#", "..#"); g('5', "###", "#..", "##.", "..#", "##.")
        g('6', ".##", "#..", "###", "#.#", "###"); g('7', "###", "..#", ".#.", ".#.", ".#.")
        g('8', "###", "#.#", "###", "#.#", "###"); g('9', "###", "#.#", "###", "..#", "##.")
        g('-', "...", "...", "###", "...", "..."); g('.', "...", "...", "...", "...", ".#.")
        g(',', "...", "...", "...", ".#.", "#.."); g('\'', ".#.", ".#.", "...", "...", "...")
        g('!', ".#.", ".#.", ".#.", "...", ".#."); g('?', "##.", "..#", ".#.", "...", ".#.")
        g('(', ".#.", "#..", "#..", "#..", ".#."); g(')', ".#.", "..#", "..#", "..#", ".#.")
        g(':', "...", ".#.", "...", ".#.", "..."); g('/', "..#", "..#", ".#.", "#..", "#..")
        g('&', ".#.", "#.#", ".#.", "#.#", ".##")
    }
    private val unknown = listOf("#.#", ".#.", "#.#", ".#.", "#.#")

    /** Column bitmasks (bit 0 = top row) of [text], 3 px per character plus a 1 px gap. */
    fun columns(text: String): IntArray {
        val out = ArrayList<Int>()
        var lastUnknown = false
        for (ch in text) {
            if (ch == ' ') { repeat(2) { out += 0 }; lastUnknown = false; continue }
            val rows = glyphs[ch.uppercaseChar()]
            if (rows == null) {
                if (lastUnknown) continue // collapse runs of unsupported characters into one mark
                lastUnknown = true
            } else lastUnknown = false
            val g = rows ?: unknown
            for (x in 0 until 3) {
                var bits = 0
                for (y in 0 until 5) if (g[y][x] == '#') bits = bits or (1 shl y)
                out += bits
            }
            out += 0
        }
        return out.toIntArray()
    }
}

/**
 * Music AOD: scrolls "Title - Artist" once, then shows the chosen [MusicStyle] picture for [gap] seconds, and
 * repeats. With showName = false (or no title) it only shows the picture.
 *
 * params: text (string), showName (bool), style ("disc"|"bars"|"wave"|"pulse"), gap (seconds, default 15)
 */
internal class MusicAod(p: Params, private val n: Int) : ProceduralGenerator {
    private val showName = p.b("showName", true) && p.s("text", "").isNotBlank()
    private val style = MusicStyle.fromKey(p.s("style", "disc"))
    private val gap = p.f("gap", 15f).coerceIn(1f, 120f)
    private val cols = PixelFont.columns(p.s("text", ""))
    private val scale = if (n >= 20) 2 else 1
    private val textW = cols.size * scale
    private val speed = 9f * scale                         // pixels per second
    private val scrollSec = (textW + n) / speed
    private val c = (n - 1) / 2f
    private val radius = n / 2f - 0.15f

    override fun start(initialInput: Float) {}

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        java.util.Arrays.fill(out, 0)
        if (!showName) { art(timeSec, out); return }
        val cycle = scrollSec + gap
        val t = timeSec % cycle
        if (t < scrollSec) text(t, out) else art(t - scrollSec, out)
    }

    private fun text(t: Float, out: IntArray) {
        val offset = n - (t * speed).toInt()               // x of the first text column
        val top = (n - 5 * scale) / 2
        for (x in 0 until n) {
            val col = (x - offset) / scale
            if (x - offset < 0 || col >= cols.size) continue
            val bits = cols[col]
            for (y in 0 until 5 * scale) {
                if (bits and (1 shl (y / scale)) == 0) continue
                val py = top + y
                if (py !in 0 until n) continue
                if (hypot(x - c, py - c) > radius) continue
                out[py * n + x] = 255
            }
        }
    }

    private fun art(t: Float, out: IntArray) {
        when (style) {
            MusicStyle.DISC -> disc(t, out)
            MusicStyle.BARS -> bars(t, out)
            MusicStyle.WAVE -> wave(t, out)
            MusicStyle.PULSE -> pulse(t, out)
        }
    }

    /** Minimal disc: faint outer ring, one comet with a fading trail sweeping round, a centre dot. */
    private fun disc(t: Float, out: IntArray) {
        val head = t * 3.0f                                 // comet position, radians (increases clockwise on screen)
        val twoPi = 2f * PI.toFloat()
        val bandW = maxOf(1.2f, n * 0.16f)                  // thickness of the lane the comet travels in
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = hypot(x - c, y - c)
                if (d > radius) continue
                var v = 0f
                if (d > radius - 1f) v = 28f                // faint outer ring
                if (d > radius - bandW) {
                    val lag = (((head - atan2(y - c, x - c)) % twoPi) + twoPi) % twoPi  // 0 at the comet, grows behind it
                    v = maxOf(v, 255f * exp(-lag / 0.85f))  // smooth gradient trail
                }
                if (d <= 0.9f) v = 110f                     // centre dot
                out[y * n + x] = v.toInt().coerceIn(0, 255)
            }
        }
    }

    /** A few equaliser bars that grow from the middle line; brighter towards the tips. */
    private fun bars(t: Float, out: IntArray) {
        val count = 5
        val pitch = 2 * scale
        val x0 = c - (count - 1) / 2f * pitch
        for (k in 0 until count) {
            val v = ((sin(t * (2.3f + 0.9f * k) + k * 1.3f) + sin(t * (3.1f - 0.4f * k) + k * 2.1f)) / 4f + 0.5f)
                .coerceIn(0.12f, 1f)
            val half = (v * radius * 0.7f).coerceAtLeast(0.5f)
            for (w in 0 until scale) {
                val x = (x0 + k * pitch).toInt() + w
                if (x !in 0 until n) continue
                for (y in 0 until n) {
                    val dy = abs(y - c)
                    if (dy > half || hypot(x - c, y - c) > radius) continue
                    out[y * n + x] = (90f + 165f * (dy / half)).toInt().coerceIn(0, 255)
                }
            }
        }
    }

    /** One travelling sine line, anti-aliased over two pixels per column (smooth gradient, very few LEDs). */
    private fun wave(t: Float, out: IntArray) {
        val amp = n * 0.22f
        val lambda = n * 0.9f
        for (x in 0 until n) {
            val yf = c + amp * sin(2f * PI.toFloat() * x / lambda - t * 3.2f)
            val y0 = floor(yf).toInt()
            val f = yf - y0
            for (k in 0..1) {
                val yy = y0 + k
                val w = if (k == 0) 1f - f else f
                if (yy !in 0 until n || hypot(x - c, yy - c) > radius) continue
                val i = yy * n + x
                out[i] = maxOf(out[i], (255f * w).toInt())
            }
        }
    }

    /** Rings expanding from a small centre dot and fading out, like a beat. */
    private fun pulse(t: Float, out: IntArray) {
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = hypot(x - c, y - c)
                if (d > radius) continue
                var v = if (d < 0.6f) 200f else 0f
                for (k in 0..1) {
                    val phase = (t * 0.7f + k * 0.5f) % 1f
                    val r = phase * radius
                    v = maxOf(v, 255f * (1f - phase) * exp(-((d - r) * (d - r)) / 0.35f))
                }
                out[y * n + x] = if (v < 35f) 0 else v.toInt().coerceIn(0, 255)   // drop the faint tail: fewer LEDs
            }
        }
    }
}

/**
 * Volume bars: the same five bars as the music pattern (grow from the middle line, brighter towards the tips),
 * but their height follows the volume level and they dance on top of it.
 * params: smoothMs (level easing), jitterSpeed, fixed (0..1 constant level, used by the 100% variant)
 */
internal class VolumeBars(p: Params, private val n: Int) : ProceduralGenerator {
    private val smoothSec = p.f("smoothMs", 200f).coerceAtLeast(1f) / 1000f
    private val jitter = p.f("jitterSpeed", 1f)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val scale = if (n >= 20) 2 else 1
    private val c = (n - 1) / 2f
    private val radius = n / 2f - 0.15f
    private var level = 0f

    override fun start(initialInput: Float) {
        level = (if (fixed >= 0f) fixed else initialInput).coerceIn(0f, 1f)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        java.util.Arrays.fill(out, 0)
        val target = (if (fixed >= 0f) fixed else input).coerceIn(0f, 1f)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val t = timeSec * jitter
        val count = 5
        val pitch = 2 * scale
        val x0 = c - (count - 1) / 2f * pitch
        for (k in 0 until count) {
            val w = 0.55f + 0.45f * (0.5f * sin(t * (2.3f + 0.9f * k) + k * 1.3f) +
                0.5f * sin(t * (3.1f - 0.4f * k) + k * 2.1f))
            val half = if (level < 0.02f) 0.5f else maxOf(0.8f, level * radius * w)
            for (wd in 0 until scale) {
                val x = (x0 + k * pitch).toInt() + wd
                if (x !in 0 until n) continue
                for (y in 0 until n) {
                    val dy = abs(y - c)
                    if (dy > half || hypot(x - c, y - c) > radius) continue
                    out[y * n + x] = (90f + 165f * (dy / half)).toInt().coerceIn(0, 255)
                }
            }
        }
    }
}

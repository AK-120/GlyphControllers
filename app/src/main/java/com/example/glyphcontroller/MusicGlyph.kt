package com.example.glyphcontroller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

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
 * Music AOD: scrolls "Title - Artist" once, then shows a rotating vinyl disc for [gap] seconds, and repeats.
 * With showName = false (or no title) it only shows the rotating disc.
 *
 * params: text (string), showName (bool), gap (seconds the disc is shown between scrolls, default 15)
 */
internal class MusicAod(p: Params, private val n: Int) : ProceduralGenerator {
    private val showName = p.b("showName", true) && p.s("text", "").isNotBlank()
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
        if (!showName) { disc(timeSec, out); return }
        val cycle = scrollSec + gap
        val t = timeSec % cycle
        if (t < scrollSec) text(t, out) else disc(t - scrollSec, out)
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

    private fun disc(t: Float, out: IntArray) {
        val rot = t * 3.0f                                  // radians per second
        val labelR = n * 0.17f
        val holeR = maxOf(0.8f, n * 0.07f)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = hypot(x - c, y - c)
                if (d > radius) continue
                var v = when {
                    d < holeR -> 0f
                    d <= labelR -> 190f
                    d > radius - 1f -> 150f
                    else -> if (d.toInt() % 2 == 0) 70f else 35f
                }
                if (d > labelR && d < radius - 1f) {
                    val a = atan2(y - c, x - c)
                    // brightest highlight on two opposite sides, so the rotation reads clearly
                    val d1 = angDiff(a, rot)
                    val d2 = angDiff(a, rot + PI.toFloat())
                    val w = exp(-(d1 * d1) / 0.12f) + exp(-(d2 * d2) / 0.12f)
                    v += 185f * w
                }
                out[y * n + x] = v.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun angDiff(a: Float, b: Float): Float {
        var d = (a - b) % (2f * PI.toFloat())
        if (d > PI) d -= 2f * PI.toFloat()
        if (d < -PI) d += 2f * PI.toFloat()
        return abs(d)
    }
}

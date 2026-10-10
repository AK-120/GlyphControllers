package com.example.glyphcontroller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Code-driven animations whose look is parameterised from JSON (they react to a live 0..1 input,
 * which pre-baked frames cannot do: volume percentage, battery level).
 */
interface ProceduralGenerator {
    /** Called once when playback starts. [initialInput] is where an input-driven animation should start from. */
    fun start(initialInput: Float)

    /** Writes n*n intensities (0..255) into [out]. [timeSec] is already scaled by the speed setting. */
    fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray)
}

object ProceduralGenerators {
    val NAMES = setOf(
        "level_fill", "charge_flow", "breathing_ring", "orbit", "ripple",
        "ring_gauge", "eq_bars", "radial_fill", "signal_steps", "speaker", "spiral_fill", "charge_percent", "boost",
        "music_aod", "volume_bars"
    )

    fun create(name: String, p: Params, n: Int): ProceduralGenerator? = when (name) {
        "level_fill" -> LevelFill(p, n)
        "charge_flow" -> ChargeFlow(p, n)
        "breathing_ring" -> BreathingRing(p, n)
        "orbit" -> Orbit(p, n)
        "ripple" -> Ripple(p, n)
        "ring_gauge" -> RingGauge(p, n)
        "eq_bars" -> EqBars(p, n)
        "radial_fill" -> RadialFill(p, n)
        "signal_steps" -> SignalSteps(p, n)
        "speaker" -> Speaker(p, n)
        "spiral_fill" -> SpiralFill(p, n)
        "charge_percent" -> ChargePercent(p, n)
        "boost" -> Boost(p, n).takeIf { it.valid }
        "music_aod" -> MusicAod(p, n)
        "volume_bars" -> VolumeBars(p, n)
        else -> null
    }
}

private fun clamp255(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else v.toInt()
private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun hash(x: Int, y: Int, z: Int): Int {
    var h = x * 374761393 + y * 668265263 + z * 2147483647.toInt()
    h = (h xor (h ushr 13)) * 1274126177
    return h xor (h ushr 16)
}

/** Shared helpers for n x n round matrices. */
private class Grid(val n: Int) {
    val c = (n - 1) / 2f
    val rOuter = n / 2f

    fun dist(x: Int, y: Int) = hypot(x - c, y - c)
    fun inDisc(x: Int, y: Int) = dist(x, y) <= rOuter - 0.15f
    fun onOutline(x: Int, y: Int): Boolean {
        val d = dist(x, y)
        return d <= rOuter - 0.15f && d > rOuter - 1.15f
    }
}

/**
 * Liquid-style fill that follows a 0..1 level (volume percentage). Smoothly eases to the target,
 * optional surface wave, optional sparkle at 100%.
 *
 * params: shape ("disc"|"bar"), smoothMs, waveAmp, waveSpeed, outline (0..255), shimmer (bool),
 *         fixed (0..1 constant level, ignores input), barThickness (rows, bar shape)
 */
private class LevelFill(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val bar = p.s("shape", "disc") == "bar"
    private val smoothSec = p.f("smoothMs", 240f).coerceAtLeast(1f) / 1000f
    private val waveAmp = p.f("waveAmp", 0.8f)
    private val waveSpeed = p.f("waveSpeed", 0.8f)
    private val outline = p.f("outline", 26f)
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val thickness = p.i("barThickness", 5).coerceIn(1, n)
    private var level = 0f

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        val hump = clamp01(4f * level * (1f - level))
        val barTop = (n - thickness) / 2
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                val inside: Boolean
                val edge: Boolean
                val d: Float
                if (bar) {
                    inside = y >= barTop && y < barTop + thickness
                    edge = inside && (y == barTop || y == barTop + thickness - 1 || x == 0 || x == n - 1)
                    d = level * n - (x + 0.5f)               // >0 => covered
                } else {
                    inside = g.inDisc(x, y)
                    edge = g.onOutline(x, y)
                    val wave = waveAmp * hump * sin(x * 0.85f + timeSec * waveSpeed * 2f * PI.toFloat())
                    d = (y + 0.5f) - ((1f - level) * n + wave)   // >0 => below the surface
                }
                if (!inside) {
                    out[i] = 0
                    continue
                }
                var v = clamp01(d + 0.5f) * 200f
                if (d > -1.2f) v += exp(-abs(d) * 1.4f) * 55f            // soft glow on the surface
                if (edge) v = max(v, outline)
                if (shimmer && full && (hash(x, y, spark) and 7) == 0) v = 255f
                out[i] = clamp255(v)
            }
        }
    }
}

/**
 * Battery-level fill with bands flowing upwards ("charging flow").
 * params: flowSpeed, bandFreq, smoothMs, outline
 */
private class ChargeFlow(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val flowSpeed = p.f("flowSpeed", 1f)
    private val bandFreq = p.f("bandFreq", 0.9f)
    private val smoothSec = p.f("smoothMs", 600f).coerceAtLeast(1f) / 1000f
    private val outline = p.f("outline", 26f)
    private var level = 0f

    override fun start(initialInput: Float) {
        level = clamp01(initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        val n = g.n
        val surface = (1f - level) * n
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (!g.inDisc(x, y)) {
                    out[i] = 0
                    continue
                }
                val d = (y + 0.5f) - surface
                var v = 0f
                if (d > -0.5f) {
                    val cover = clamp01(d + 0.5f)
                    val band = 0.5f + 0.5f * sin(y * bandFreq * 1.6f - timeSec * flowSpeed * 2f * PI.toFloat() + x * 0.25f)
                    v = cover * (95f + 130f * band)
                    if (d < 1.2f) v += 45f                                  // bright leading edge
                }
                if (g.onOutline(x, y)) v = max(v, outline)
                out[i] = clamp255(v)
            }
        }
    }
}

/** params: period (s), minRadius, maxRadius, thickness, minIntensity (0..1), core (bool) */
private class BreathingRing(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val period = p.f("period", 4.5f).coerceAtLeast(0.2f)
    private val rMin = p.f("minRadius", 1f)
    private val rMax = p.f("maxRadius", n / 2f - 0.8f)
    private val thick = p.f("thickness", 1f).coerceAtLeast(0.3f)
    private val minI = p.f("minIntensity", 0.25f)
    private val core = p.b("core", true)

    override fun start(initialInput: Float) {}

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val phase = 0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * timeSec / period) // 0..1..0
        val radius = lerp(rMin, rMax, phase)
        val gain = lerp(minI, 1f, phase)
        val n = g.n
        for (y in 0 until n) for (x in 0 until n) {
            val d = g.dist(x, y)
            var v = max(0f, 1f - abs(d - radius) / thick) * 255f * gain
            if (core) v = max(v, max(0f, 1f - d / 1.3f) * 160f)
            out[y * n + x] = clamp255(v)
        }
    }
}

/** params: period (s), radius, tail (LEDs), ring (0..1 faint guide ring) */
private class Orbit(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val period = p.f("period", 3f).coerceAtLeast(0.2f)
    private val radius = p.f("radius", n / 2f - 1.5f)
    private val tailRad = p.i("tail", 7).coerceIn(1, 40) * 0.12f
    private val guide = p.f("ring", 0.08f)

    override fun start(initialInput: Float) {}

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val head = (timeSec / period) * 2f * PI.toFloat()
        val n = g.n
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x - g.c
            val dy = y - g.c
            val d = hypot(dx, dy)
            var v = max(0f, 1f - abs(d - radius) / 0.9f) * 255f * guide
            if (abs(d - radius) < 1.1f) {
                var delta = head - atan2(dy, dx)                     // angular distance behind the head
                delta -= 2f * PI.toFloat() * floor(delta / (2f * PI.toFloat()))
                if (delta < tailRad) v = max(v, (1f - delta / tailRad) * 255f)
            }
            out[y * n + x] = clamp255(v)
        }
    }
}

/** Expanding rings from the centre. params: rings, maxRadius, thickness. Period = one pass. */
private class Ripple(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val rings = p.i("rings", 2).coerceIn(1, 4)
    private val maxR = p.f("maxRadius", n / 2f)
    private val thick = p.f("thickness", 1f).coerceAtLeast(0.3f)
    private val period = p.f("period", 0.8f).coerceAtLeast(0.1f)

    override fun start(initialInput: Float) {}

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val n = g.n
        for (y in 0 until n) for (x in 0 until n) {
            val d = g.dist(x, y)
            var v = 0f
            for (k in 0 until rings) {
                val prog = ((timeSec / period) - k * 0.33f)
                if (prog < 0f) continue
                val frac = prog - floor(prog)
                val r = frac * maxR
                v = max(v, max(0f, 1f - abs(d - r) / thick) * 255f * (1f - frac))
            }
            out[y * n + x] = clamp255(min(255f, v))
        }
    }
}

// ====================================================================== volume-style generators
// All of these follow a 0..1 input (volume) with the same easing, and accept "fixed" (a constant level that
// ignores the input) so a "max" variant can pin them at 100% and add shimmer.

private val TWO_PI: Float = (2.0 * PI).toFloat()

/**
 * Dial around the rim that fills clockwise.
 * params: radius, thickness, startDeg (0 = right, 90 = down, 135 = bottom-left), sweepDeg (10..360), smoothMs,
 *         track (0..255 faint unfilled arc), head (glow at the tip), core (centre dot follows level),
 *         shimmer, fixed
 */
private class RingGauge(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val radius = p.f("radius", n / 2f - 1.1f)
    private val thick = p.f("thickness", 1.0f).coerceAtLeast(0.3f)
    private val startRad = p.f("startDeg", 135f) * PI.toFloat() / 180f
    private val sweepRad = p.f("sweepDeg", 270f).coerceIn(10f, 360f) * PI.toFloat() / 180f
    private val smoothSec = p.f("smoothMs", 260f).coerceAtLeast(1f) / 1000f
    private val track = p.f("track", 28f)
    private val head = p.b("head", true)
    private val core = p.b("core", true)
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private var level = 0f

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val arcLen = radius * sweepRad
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (!g.inDisc(x, y)) {
                    out[i] = 0
                    continue
                }
                val dx = x - g.c
                val dy = y - g.c
                val d = hypot(dx, dy)
                val prof = max(0f, 1f - abs(d - radius) / thick)
                var v = 0f
                if (prof > 0f) {
                    var rel = atan2(dy, dx) - startRad
                    rel -= TWO_PI * floor(rel / TWO_PI)                      // 0..2pi, clockwise from the start
                    if (rel <= sweepRad + 0.02f) {
                        val frac = rel / sweepRad
                        val lit = if (level <= 0.001f) 0f else clamp01((level - frac) * arcLen + 0.5f)
                        v = prof * (track + (255f - track) * lit)
                        if (head && !full && level > 0.01f) v += prof * exp(-abs(level - frac) * arcLen * 0.9f) * 60f
                        if (shimmer && full && (hash(x, y, spark) and 7) == 0) v = 255f
                    }
                }
                if (core && d < 0.6f) v = max(v, 30f + 180f * level)
                out[i] = clamp255(v)
            }
        }
    }
}

/**
 * Equaliser bars whose height scales with the level and which dance on their own.
 * params: bars, barWidth, gap, mirror (grow from the centre row), peaks (falling peak dots, not in mirror),
 *         jitterSpeed, riseMs, fallMs, smoothMs (level), floor (idle dots 0..255), shimmer, fixed
 */
private class EqBars(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val bars = p.i("bars", 7).coerceIn(2, n)
    private val bw = p.i("barWidth", 1).coerceIn(1, 4)
    private val gap = p.i("gap", 1).coerceIn(0, 4)
    private val mirror = p.b("mirror", false)
    private val peaks = p.b("peaks", true)
    private val jitter = p.f("jitterSpeed", 1f)
    private val riseSec = p.f("riseMs", 70f).coerceAtLeast(1f) / 1000f
    private val fallSec = p.f("fallMs", 170f).coerceAtLeast(1f) / 1000f
    private val smoothSec = p.f("smoothMs", 240f).coerceAtLeast(1f) / 1000f
    private val floorI = p.f("floor", 36f)
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val total = bars * bw + (bars - 1) * gap
    private val x0 = (n - total) / 2
    private val h = FloatArray(bars)
    private val peak = FloatArray(bars)
    private var level = 0f

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
        h.fill(0f); peak.fill(0f)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val rows = (n - 2).toFloat()
        val t = timeSec * jitter
        for (k in 0 until bars) {
            val w = 0.55f + 0.45f * (0.5f * sin(t * (2.1f + 0.53f * k) + k * 1.9f) +
                0.5f * sin(t * (3.7f - 0.21f * k) + k * 0.7f))
            val tgt = if (level < 0.02f) 0f else max(1f, level * rows * w)
            val tau = if (tgt > h[k]) riseSec else fallSec
            h[k] += (tgt - h[k]) * (1f - exp(-dtSec / tau))
            peak[k] = max(peak[k] - dtSec * 5f, h[k])
        }
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        val pitch = bw + gap
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                out[i] = 0
                if (!g.inDisc(x, y) || y < 1 || y > n - 2) continue
                val rel = x - x0
                if (rel < 0 || rel >= total || rel % pitch >= bw) continue
                val k = rel / pitch
                val fromBottom = (n - 2) - y
                var v = 0f
                if (mirror) {
                    val ry = abs(y - g.c)
                    v = clamp01(h[k] / 2f - ry + 0.5f) * (150f + 105f * (1f - ry / (rows / 2f)).coerceIn(0f, 1f))
                    if (v == 0f && abs(ry) < 0.5f) v = floorI
                } else {
                    v = clamp01(h[k] - fromBottom) * (150f + 105f * fromBottom / (rows - 1f))
                    if (peaks && peak[k] > h[k] + 0.9f && (peak[k] + 0.5f).toInt() == fromBottom + 1) v = 255f
                    if (v == 0f && fromBottom == 0) v = floorI
                }
                if (shimmer && full && v > 0f && (hash(x, y, spark) and 7) == 0) v = 255f
                out[i] = clamp255(v)
            }
        }
    }
}

/**
 * Disc (or ring) that grows from the centre as the level rises.
 * params: smoothMs, hollow (just a travelling ring), thickness, glow, pulse (0..1), pulseSpeed, outline,
 *         maxRadius, shimmer, fixed
 */
private class RadialFill(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val smoothSec = p.f("smoothMs", 280f).coerceAtLeast(1f) / 1000f
    private val hollow = p.b("hollow", false)
    private val thick = p.f("thickness", 1.1f).coerceAtLeast(0.3f)
    private val glow = p.f("glow", 55f)
    private val pulse = p.f("pulse", 0.25f).coerceIn(0f, 1f)
    private val pulseSpeed = p.f("pulseSpeed", 0.9f)
    private val outline = p.f("outline", 22f)
    private val rMax = p.f("maxRadius", n / 2f - 0.3f)
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private var level = 0f

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val r = level * rMax
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (!g.inDisc(x, y)) {
                    out[i] = 0
                    continue
                }
                val d = g.dist(x, y)
                var v: Float
                if (hollow) {
                    v = max(0f, 1f - abs(d - r) / thick) * 255f
                } else {
                    v = clamp01(r - d + 0.5f) * 190f
                    if (level > 0.01f) v += exp(-abs(d - r) * 1.3f) * glow
                    val m = 1f - pulse + pulse * (0.5f + 0.5f * sin(TWO_PI * pulseSpeed * timeSec - d * 0.5f))
                    v *= m
                }
                if (d < 0.6f) v = max(v, 40f + 170f * level)
                if (g.onOutline(x, y)) v = max(v, outline)
                if (shimmer && full && (hash(x, y, spark) and 7) == 0) v = 255f
                out[i] = clamp255(v)
            }
        }
    }
}

/**
 * Signal-strength style staircase; bars light up one by one (the last one fades in).
 * params: steps, barWidth, gap, minHeight, maxHeight, smoothMs, dim (unlit bars 0..255), wave (0..1 travelling
 *         brightness ripple on lit bars), shimmer, fixed
 */
private class SignalSteps(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val steps = p.i("steps", 5).coerceIn(2, n)
    private val bw = p.i("barWidth", 2).coerceIn(1, 6)
    private val gap = p.i("gap", 1).coerceIn(0, 4)
    private val minH = p.i("minHeight", 2).coerceIn(1, n)
    private val maxH = p.i("maxHeight", n - 3).coerceIn(minH, n)
    private val smoothSec = p.f("smoothMs", 200f).coerceAtLeast(1f) / 1000f
    private val dim = p.f("dim", 30f)
    private val wave = p.f("wave", 0f).coerceIn(0f, 1f)
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val total = steps * bw + (steps - 1) * gap
    private val x0 = (n - total) / 2
    private val bottom = (n - maxH) / 2 + maxH - 1
    private var level = 0f

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        val pitch = bw + gap
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                out[i] = 0
                if (!g.inDisc(x, y)) continue
                val rel = x - x0
                if (rel < 0 || rel >= total || rel % pitch >= bw) continue
                val k = rel / pitch
                val hk = minH + (maxH - minH) * k / (steps - 1)
                if (y > bottom || y <= bottom - hk) continue
                val amt = clamp01(level * steps - k)
                var v = dim + (255f - dim) * amt
                if (amt > 0f && wave > 0f) v *= 1f - wave + wave * (0.5f + 0.5f * sin(TWO_PI * 0.6f * timeSec - k * 0.9f))
                if (shimmer && full && (hash(x, y, spark) and 7) == 0) v = 255f
                out[i] = clamp255(v)
            }
        }
    }
}

/**
 * Speaker icon; sound waves appear with the level, a small X shows when muted.
 * Layout is written for the 13 x 13 matrix and stays centred on other sizes.
 * params: waves (1..3), waveSpeed, body (0..255), showMute, smoothMs, shimmer, fixed
 */
private class Speaker(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val waves = p.i("waves", 3).coerceIn(1, 3)
    private val waveSpeed = p.f("waveSpeed", 1.2f)
    private val bodyI = p.f("body", 210f)
    private val showMute = p.b("showMute", true)
    private val smoothSec = p.f("smoothMs", 220f).coerceAtLeast(1f) / 1000f
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val cy = n / 2
    private val bodyMask = BooleanArray(n * n)
    private val crossMask = BooleanArray(n * n)
    private val radii = floatArrayOf(2.4f, 4.2f, 6.0f)
    private var level = 0f

    init {
        fun setBody(x: Int, y0: Int, y1: Int) {
            for (y in y0..y1) if (x in 0 until n && y in 0 until n) bodyMask[y * n + x] = true
        }
        for (x in 1..3) setBody(x, cy - 1, cy + 1)
        setBody(4, cy - 2, cy + 2)
        setBody(5, cy - 3, cy + 3)
        for (j in 0..4) {
            val x = 8 + j
            val y1 = cy - 2 + j
            val y2 = cy + 2 - j
            if (x < n) {
                if (y1 in 0 until n) crossMask[y1 * n + x] = true
                if (y2 in 0 until n) crossMask[y2 * n + x] = true
            }
        }
    }

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        val muteA = if (showMute) clamp01(1f - level * 25f) else 0f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (!g.inDisc(x, y)) {
                    out[i] = 0
                    continue
                }
                var v = 0f
                if (bodyMask[i]) {
                    v = bodyI
                } else {
                    val dx = x - 5f
                    val dy = (y - cy).toFloat()
                    val d = hypot(dx, dy)
                    val ang = atan2(dy, dx)
                    if (dx > 0f && abs(ang) <= 0.9f) {
                        for (k in 0 until waves) {
                            val prof = max(0f, 1f - abs(d - radii[k]) / 0.8f)
                            if (prof <= 0f) continue
                            val amt = sqrt(clamp01(level * waves - k))
                            val pulseK = 0.8f + 0.2f * sin(TWO_PI * waveSpeed * timeSec - k * 0.9f)
                            v = max(v, prof * (14f + (255f - 14f) * amt * pulseK))
                        }
                    }
                }
                if (muteA > 0f && crossMask[i]) v = max(v, 200f * muteA)
                if (shimmer && full && v > 0f && !bodyMask[i] && (hash(x, y, spark) and 7) == 0) v = 255f
                out[i] = clamp255(v)
            }
        }
    }
}

/**
 * Pixels light up along a spiral from the centre outwards (or inwards) as the level rises.
 * params: smoothMs, trail (pixels over which the bright head fades), dim (unlit pixels 0..255), pitch (spiral
 *         spacing), reverse, chase (pixels/s of a travelling highlight when full), shimmer, fixed
 */
private class SpiralFill(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val smoothSec = p.f("smoothMs", 260f).coerceAtLeast(1f) / 1000f
    private val trail = p.f("trail", 8f).coerceAtLeast(1f)
    private val dim = p.f("dim", 20f)
    private val pitch = p.f("pitch", 2.5f).coerceAtLeast(0.2f)
    private val reverse = p.b("reverse", false)
    private val chase = p.f("chase", 0f)
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val rank = IntArray(n * n) { -1 }
    private var total = 0
    private var level = 0f

    init {
        val cells = ArrayList<Int>()
        for (y in 0 until n) for (x in 0 until n) if (g.inDisc(x, y)) cells.add(y * n + x)
        fun key(idx: Int): Float {
            val dx = (idx % n) - g.c
            val dy = (idx / n) - g.c
            var a = atan2(dy, dx)
            if (a < 0f) a += TWO_PI
            return hypot(dx, dy) + a / TWO_PI * pitch
        }
        cells.sortBy { key(it) }
        total = cells.size
        for ((r, idx) in cells.withIndex()) rank[idx] = if (reverse) total - 1 - r else r
    }

    override fun start(initialInput: Float) {
        level = clamp01(if (fixed >= 0f) fixed else initialInput)
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val target = clamp01(if (fixed >= 0f) fixed else input)
        level += (target - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(target - level) < 0.0005f) level = target
        val n = g.n
        val full = level > 0.995f
        val spark = floor(timeSec * 9f).toInt()
        val head = level * total
        val chaseAt = if (chase > 0f) (timeSec * chase) % total else -1f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                val r = rank[i]
                if (r < 0) {
                    out[i] = 0
                    continue
                }
                val age = head - r
                var v = dim
                if (age > 0f) {
                    val tail = 1f - clamp01((age - 1f) / trail)
                    v = dim + (255f - dim) * clamp01(age) * (0.5f + 0.5f * tail)
                }
                if (full && chaseAt >= 0f) {
                    var da = chaseAt - r
                    if (da < 0f) da += total
                    if (da < trail) v = max(v, 255f * (1f - da / trail))
                }
                if (shimmer && full && (hash(x, y, spark) and 7) == 0) v = 255f
                out[i] = clamp255(v)
            }
        }
    }
}

// ====================================================================== battery percentage

/** 3 x 5 pixel digits; each row is a 3-bit mask, the left column is the highest bit. */
private val DIGIT_FONT = arrayOf(
    intArrayOf(7, 5, 5, 5, 7), // 0
    intArrayOf(2, 6, 2, 2, 7), // 1
    intArrayOf(7, 1, 7, 4, 7), // 2
    intArrayOf(7, 1, 7, 1, 7), // 3
    intArrayOf(5, 5, 7, 1, 1), // 4
    intArrayOf(7, 4, 7, 1, 7), // 5
    intArrayOf(7, 4, 7, 5, 7), // 6
    intArrayOf(7, 1, 2, 2, 2), // 7
    intArrayOf(7, 5, 7, 5, 7), // 8
    intArrayOf(7, 5, 7, 1, 7)  // 9
)

/**
 * Battery percentage as pixel digits with an animated ring around them. The input is the battery level
 * 0..1 (the controller feeds the real level while charging). The digits roll like an odometer when the
 * number changes; the ring fills with the level.
 *
 * params: ringRadius, thickness, progress (ring fills with the level), track (0..255 unfilled ring), ringI,
 *         segments (0 = solid ring, N = ring made of N dots), flow (0..1 energy wave on the filled part),
 *         flowSpeed, comets (0..3 sparks circling the ring), cometPeriod (s), tail (fraction of the circle),
 *         pulse (0..1 digits breathe), pulseSpeed, digitI, tide (0..255 battery-level water behind the digits),
 *         scan (0..255 bright band sweeping over the digits), scanSpeed, rollMs, smoothMs (ring),
 *         shimmer (sparkling ring at 100%), fixed (0..1 constant level, ignores the input)
 */
private class ChargePercent(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val ringR = p.f("ringRadius", n / 2f - 1.0f)
    private val thick = p.f("thickness", 0.9f).coerceAtLeast(0.3f)
    private val progress = p.b("progress", true)
    private val track = p.f("track", 26f)
    private val ringI = p.f("ringI", 190f)
    private val segments = p.i("segments", 0).coerceIn(0, 40)
    private val flow = p.f("flow", 0f).coerceIn(0f, 1f)
    private val flowSpeed = p.f("flowSpeed", 0.8f)
    private val comets = p.i("comets", 1).coerceIn(0, 3)
    private val cometPeriod = p.f("cometPeriod", 2.4f).coerceAtLeast(0.2f)
    private val tail = p.f("tail", 0.2f).coerceIn(0.03f, 0.9f)
    private val pulse = p.f("pulse", 0f).coerceIn(0f, 1f)
    private val pulseSpeed = p.f("pulseSpeed", 0.5f)
    private val digitI = p.f("digitI", 235f)
    private val tide = p.f("tide", 0f)
    private val scan = p.f("scan", 0f)
    private val scanSpeed = p.f("scanSpeed", 6f)
    private val rollSec = p.f("rollMs", 300f).coerceAtLeast(1f) / 1000f
    private val smoothSec = p.f("smoothMs", 700f).coerceAtLeast(1f) / 1000f
    private val shimmer = p.b("shimmer", false)
    private val fixed = if (p.has("fixed")) p.f("fixed", 1f) else -1f
    private val digits = IntArray(n * n)
    private var level = 0f
    private var cur = 0
    private var prev = 0
    private var roll = 1f

    override fun start(initialInput: Float) {
        val v = clamp01(if (fixed >= 0f) fixed else initialInput)
        level = v
        cur = (v * 100f).roundToInt()
        prev = cur
        roll = 1f
    }

    /** Draws one glyph into [digits]; only rows inside the 5-row window starting at [top] are kept. */
    private fun glyph(ch: Int, x0: Int, top: Int, yShift: Int) {
        if (ch < 0 || ch > 9) return
        val n = g.n
        for (r in 0 until 5) {
            val y = top + r + yShift
            if (y < top || y >= top + 5 || y < 0 || y >= n) continue
            val bits = DIGIT_FONT[ch][r]
            val v = (digitI * (1f - 0.16f * r / 4f)).toInt()
            for (c in 0 until 3) {
                val x = x0 + c
                if (x < 0 || x >= n) continue
                if (((bits shr (2 - c)) and 1) == 1) digits[y * n + x] = v
            }
        }
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val n = g.n
        val raw = clamp01(if (fixed >= 0f) fixed else input)
        val pct = (raw * 100f).roundToInt().coerceIn(0, 100)
        if (pct != cur) { prev = cur; cur = pct; roll = 0f }
        if (roll < 1f) roll = min(1f, roll + dtSec / rollSec)
        level += (raw - level) * (1f - exp(-dtSec / smoothSec))
        if (abs(raw - level) < 0.0005f) level = raw

        // ---- digits (right-aligned slots; a slot whose digit changed rolls up)
        java.util.Arrays.fill(digits, 0)
        val curS = cur.toString()
        val prevS = prev.toString()
        val slots = if (roll < 1f) max(curS.length, prevS.length) else curS.length
        val totalW = slots * 3 + (slots - 1)
        val x0 = (n - totalW) / 2
        val top = (n - 5) / 2
        val ease = roll * roll * (3f - 2f * roll)
        val shift = (ease * 6f).roundToInt()
        for (slot in 0 until slots) {
            val ci = curS.length - slots + slot
            val pi = prevS.length - slots + slot
            val cc = if (ci >= 0) curS[ci] - '0' else -1
            val pc = if (pi >= 0) prevS[pi] - '0' else -1
            val gx = x0 + slot * 4
            if (roll >= 1f || cc == pc) {
                glyph(cc, gx, top, 0)
            } else {
                glyph(pc, gx, top, -shift)
                glyph(cc, gx, top, 6 - shift)
            }
        }

        val breath = 1f - pulse * 0.5f * (0.5f - 0.5f * kotlin.math.cos(TWO_PI * pulseSpeed * timeSec))
        val arcLen = ringR * TWO_PI
        val full = pct >= 100
        val spark = floor(timeSec * 9f).toInt()
        val scanY = if (scan > 0f) (timeSec * scanSpeed) % (n + 8f) - 4f else 0f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (!g.inDisc(x, y)) {
                    out[i] = 0
                    continue
                }
                val dx = x - g.c
                val dy = y - g.c
                val d = hypot(dx, dy)
                var v = 0f

                if (tide > 0f && d < ringR - 0.6f) {
                    val surface = (1f - level) * n + 0.6f * sin(x * 0.9f + timeSec * 1.6f)
                    v = tide * clamp01((y + 0.5f) - surface + 0.5f)
                }

                val prof = max(0f, 1f - abs(d - ringR) / thick)
                if (prof > 0f) {
                    var rel = atan2(dy, dx) + PI.toFloat() / 2f              // 0 at 12 o'clock, clockwise
                    rel -= TWO_PI * floor(rel / TWO_PI)
                    val frac = rel / TWO_PI
                    var visible = true
                    var litFrac = frac
                    if (segments > 0) {
                        val sg = frac * segments
                        val within = sg - floor(sg)
                        visible = within in 0.16f..0.84f
                        litFrac = (floor(sg) + 0.5f) / segments
                    }
                    if (visible) {
                        val lit = when {
                            !progress -> 1f
                            segments > 0 -> clamp01((level - litFrac) * segments + 0.5f)
                            else -> clamp01((level - frac) * arcLen + 0.5f)
                        }
                        var rv = track + (ringI - track) * lit
                        if (lit > 0f && flow > 0f) {
                            rv *= 1f - flow + flow * (0.55f + 0.45f * sin(TWO_PI * (frac * 2f - timeSec * flowSpeed)))
                        }
                        if (progress && segments == 0 && level > 0.01f && !full) {
                            rv += exp(-abs(level - frac) * arcLen * 0.8f) * 70f   // glowing tip
                        }
                        rv *= prof
                        for (k in 0 until comets) {
                            val head = timeSec / cometPeriod + k.toFloat() / comets
                            var delta = head - floor(head) - frac
                            delta -= floor(delta)
                            if (delta < tail) {
                                val q = 1f - delta / tail
                                rv = max(rv, prof * 255f * q * q)
                            }
                        }
                        if (shimmer && full && (hash(x, y, spark) and 7) == 0) rv = 255f
                        v = max(v, rv)
                    }
                }

                val dv = digits[i]
                if (dv > 0) {
                    var dd = dv * breath
                    if (scan > 0f) dd += scan * exp(-(y - scanY) * (y - scanY) / 1.4f)
                    v = max(v, dd)
                }
                out[i] = clamp255(v)
            }
        }
    }
}
// ====================================================================== boost (per-animation variants)

/**
 * Wraps ANY other generator and adds an effect on top, so every animation can have its own "100%" / "full" /
 * "fast" look (same shape as the base look, different effect) instead of sharing one generic variant.
 * All params are passed through to the wrapped generator, plus:
 *   base          name of the wrapped generator (required)
 *   effect        one or more of burst, ripple, sparkle, sweep, orbit, flash, strobe, negative joined with "+"
 *   effectI       effect strength 0..255 (default 170)
 *   effectPeriod  seconds per effect cycle (default 1.4)
 *   introSec      length of the "burst" intro when the variant starts (default 0.8)
 *   timeScale     plays the wrapped animation faster (>1) or calmer (<1) (default 1)
 */
private class Boost(p: Params, n: Int) : ProceduralGenerator {
    private val g = Grid(n)
    private val inner: ProceduralGenerator? =
        p.s("base", "").takeIf { it.isNotEmpty() && it != "boost" }?.let { ProceduralGenerators.create(it, p, n) }
    val valid: Boolean get() = inner != null
    private val effects = p.s("effect", "sparkle").split('+').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val strength = p.f("effectI", 170f)
    private val period = p.f("effectPeriod", 1.4f).coerceAtLeast(0.2f)
    private val intro = p.f("introSec", 0.8f).coerceAtLeast(0.05f)
    private val timeScale = p.f("timeScale", 1f).coerceAtLeast(0.05f)
    private var t0 = -1f

    override fun start(initialInput: Float) {
        inner?.start(initialInput)
        t0 = -1f
    }

    override fun render(timeSec: Float, dtSec: Float, input: Float, out: IntArray) {
        val base = inner
        if (base != null) base.render(timeSec * timeScale, dtSec * timeScale, input, out)
        else java.util.Arrays.fill(out, 0)
        if (t0 < 0f) t0 = timeSec
        val n = g.n
        val age = timeSec - t0
        val burst = "burst" in effects && age < intro
        val k = if (burst) 1f - age / intro else 0f                  // 1 -> 0 over the intro
        val ringR = if (burst) age / intro * (g.rOuter + 1.5f) else 0f
        val phase = (timeSec / period) % 1f
        val rippleR = phase * (g.rOuter + 1.5f)
        val sweepPos = phase * (2f * n + 4f) - 2f
        val head = timeSec / period
        val spark = floor(timeSec * 12f).toInt()
        val strobeLow = "strobe" in effects && (timeSec * 2f / period) % 1f >= 0.5f
        val flashM = if ("flash" in effects) 0.55f + 0.45f * (0.5f + 0.5f * sin(TWO_PI * timeSec / period)) else 1f
        val negative = "negative" in effects && phase < 0.12f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (!g.inDisc(x, y)) {
                    out[i] = 0
                    continue
                }
                var v = out[i].toFloat()
                val d = g.dist(x, y)
                if (burst) {
                    v += k * 0.55f * strength
                    v += k * strength * exp(-(d - ringR) * (d - ringR) / 0.9f)
                }
                if ("ripple" in effects) v += strength * (1f - phase) * exp(-(d - rippleR) * (d - rippleR) / 0.8f)
                if ("sweep" in effects && v > 20f) {
                    val q = (x + y) - sweepPos
                    v += strength * exp(-q * q / 2.2f)
                }
                if ("sparkle" in effects) {
                    val h = hash(x, y, spark) and 15
                    if (v > 40f && h < 2) v = 255f
                    else if (v <= 40f && h == 15 && (hash(y, x, spark) and 3) == 0) v = 80f
                }
                if ("orbit" in effects && d >= g.rOuter - 1.7f) {
                    var rel = atan2(y - g.c, x - g.c) + PI.toFloat() / 2f
                    rel -= TWO_PI * floor(rel / TWO_PI)
                    var delta = head - floor(head) - rel / TWO_PI
                    delta -= floor(delta)
                    if (delta < 0.22f) {
                        val q = 1f - delta / 0.22f
                        v = max(v, 255f * q * q)
                    }
                }
                if (strobeLow) v *= 0.4f
                v *= flashM
                if (negative) v = 255f - v
                out[i] = clamp255(v)
            }
        }
    }
}

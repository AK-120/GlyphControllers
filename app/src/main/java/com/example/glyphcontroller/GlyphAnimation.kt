package com.example.glyphcontroller

/** One LED grid (values 0..255, row-major, size n*n) shown for [durationMs]. */
class FrameStep(
    val pixels: IntArray,
    val durationMs: Long,
    /** 0..1, relative to the animation's own brightness. */
    val brightness: Float
)

/** Free-form numeric/boolean/string parameters of a procedural generator. */
class Params(private val map: Map<String, Any?>) {
    fun f(key: String, default: Float): Float = (map[key] as? Number)?.toFloat() ?: default
    fun i(key: String, default: Int): Int = (map[key] as? Number)?.toInt() ?: default
    fun b(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    fun s(key: String, default: String): String = map[key] as? String ?: default
    fun has(key: String): Boolean = map[key] != null

    companion object {
        val EMPTY = Params(emptyMap())
    }
}

sealed interface GlyphAnimation {
    val name: String
    val label: String
    /** "aod", "volume", "charging", "notification" or null. Used to fill the UI pickers. */
    val role: String?
    val loop: Boolean
    /** Only meaningful when [loop] is true; 0 = forever. */
    val loopCount: Int
    val fps: Float
    /** 0..1 */
    val brightness: Float
    /** e.g. "max" (volume 100%), "fast" / "full" (charging) -> name of another animation. */
    val variants: Map<String, String>
}

class FramesAnimation(
    override val name: String,
    override val label: String,
    override val role: String?,
    override val loop: Boolean,
    override val loopCount: Int,
    override val fps: Float,
    override val brightness: Float,
    override val variants: Map<String, String>,
    val steps: List<FrameStep>
) : GlyphAnimation

class ProceduralAnimation(
    override val name: String,
    override val label: String,
    override val role: String?,
    override val loop: Boolean,
    override val loopCount: Int,
    override val fps: Float,
    override val brightness: Float,
    override val variants: Map<String, String>,
    val generator: String,
    val params: Params,
    /** Used only for non-looping procedural animations. */
    val durationMs: Long
) : GlyphAnimation

class AnimationLibrary(val animations: Map<String, GlyphAnimation>) {
    operator fun get(name: String?): GlyphAnimation? = if (name == null) null else animations[name]
    fun byRole(role: String): List<GlyphAnimation> = animations.values.filter { it.role == role }

    companion object {
        val EMPTY = AnimationLibrary(emptyMap())
    }
}

/** Built-in last-resort animations, used when the JSON is missing/invalid so the matrix never goes dark by accident. */
object FallbackAnimations {
    private fun proc(name: String, role: String, gen: String, fps: Float, bright: Float, loop: Boolean = true,
                     params: Map<String, Any?> = emptyMap(), duration: Long = 1500L) =
        ProceduralAnimation(name, name, role, loop, 0, fps, bright, emptyMap(), gen, Params(params), duration)

    fun forRole(role: String): GlyphAnimation = when (role) {
        "volume" -> proc("fallback_volume", role, "level_fill", 30f, 1f)
        "charging" -> proc("fallback_charging", role, "charge_flow", 20f, 0.8f)
        "notification" -> proc("fallback_notification", role, "ripple", 30f, 1f, loop = false)
        else -> proc("fallback_aod", role, "breathing_ring", 15f, 0.6f)
    }
}

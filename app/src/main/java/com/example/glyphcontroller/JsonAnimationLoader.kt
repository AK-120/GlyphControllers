package com.example.glyphcontroller

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads animation definitions from JSON.
 *
 * Sources, in order (later ones override earlier ones by animation name):
 *   1. assets/animations/ (every .json file)             (bundled defaults)
 *   2. <external files dir>/animations/ (every .json file)  (yours; adb-pushable, no permission needed)
 *
 * Nothing in here throws on bad input. Every problem becomes a human-readable entry in
 * [Result.errors] and the offending file / animation / frame is skipped.
 */
object JsonAnimationLoader {

    data class Result(val library: AnimationLibrary, val errors: List<String>)

    fun userDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "animations").apply { mkdirs() }
    }

    fun load(context: Context, n: Int): Result {
        val errors = ArrayList<String>()
        val map = LinkedHashMap<String, GlyphAnimation>()

        val assetFiles = runCatching {
            context.assets.list("animations")?.filter { it.endsWith(".json") }?.sorted() ?: emptyList()
        }.getOrDefault(emptyList())
        for (f in assetFiles) {
            val text = runCatching {
                context.assets.open("animations/$f").bufferedReader().use { it.readText() }
            }.getOrElse { errors += "assets/$f: cannot read (${it.message})"; null } ?: continue
            parseInto(map, text, "assets/$f", n, errors)
        }

        val userFiles = runCatching {
            userDir(context).listFiles { file -> file.isFile && file.extension.equals("json", true) }
                ?.sortedBy { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
        for (file in userFiles) {
            val text = runCatching { file.readText() }
                .getOrElse { errors += "${file.name}: cannot read (${it.message})"; null } ?: continue
            parseInto(map, text, file.name, n, errors)
        }

        if (map.isEmpty()) errors += "No usable animations found - using built-in fallbacks."
        return Result(AnimationLibrary(map), errors)
    }

    /** Copies a user-picked JSON file into the user animation directory. Returns an error message or null. */
    fun importFile(context: Context, uri: Uri): String? {
        return try {
            var name = "imported_${System.currentTimeMillis()}.json"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            if (!name.endsWith(".json", true)) name += ".json"
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return "Could not open the selected file."
            JSONObject(text) // validate syntax before accepting it
            File(userDir(context), name).writeText(text)
            null
        } catch (t: Throwable) {
            "Import failed: ${t.message}"
        }
    }

    /** An imported (user) animation file and the animation names it defines. */
    data class UserFile(val name: String, val animations: List<String>)

    /** Lists the imported JSON files in the user animation directory (bundled assets are never listed). */
    fun listUserFiles(context: Context): List<UserFile> {
        val files = runCatching {
            userDir(context).listFiles { f -> f.isFile && f.extension.equals("json", true) }?.sortedBy { it.name }
        }.getOrNull() ?: return emptyList()
        return files.map { f ->
            val names = runCatching {
                val keys = JSONObject(f.readText()).optJSONObject("animations")?.keys()
                if (keys == null) emptyList() else keys.asSequence().toList()
            }.getOrDefault(emptyList())
            UserFile(f.name, names)
        }
    }

    /** Deletes one imported file from the user animation directory. Returns an error message or null. */
    fun deleteUserFile(context: Context, fileName: String): String? {
        return try {
            val dir = userDir(context).canonicalFile
            val target = File(dir, fileName).canonicalFile
            if (target.parentFile != dir) return "Refusing to delete outside the animations folder."
            if (!target.exists()) return null
            if (target.delete()) null else "Could not delete $fileName."
        } catch (t: Throwable) {
            "Delete failed: ${t.message}"
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseInto(
        out: MutableMap<String, GlyphAnimation>, text: String, source: String, n: Int, errors: MutableList<String>
    ) {
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            errors += "$source: invalid JSON (${t.message?.take(120)})"
            return
        }

        val frameLib = HashMap<String, IntArray>()
        root.optJSONObject("frames")?.let { frames ->
            for (key in frames.keys()) {
                val rows = frames.optJSONObject(key)?.optJSONArray("rows") ?: frames.optJSONArray(key)
                if (rows == null) {
                    errors += "$source: frame '$key' needs a \"rows\" array"
                    continue
                }
                frameLib[key] = parseRows(rows, n, "$source/frames/$key", errors)
            }
        }

        val animations = root.optJSONObject("animations")
        if (animations == null) {
            errors += "$source: missing \"animations\" object"
            return
        }
        for (key in animations.keys()) {
            try {
                val obj = animations.optJSONObject(key)
                if (obj == null) {
                    errors += "$source: animation '$key' is not an object"
                    continue
                }
                parseAnimation(key, obj, frameLib, source, n, errors)?.let { out[key] = it }
            } catch (t: Throwable) {
                errors += "$source: animation '$key' skipped (${t.message})"
            }
        }
    }

    private fun parseAnimation(
        name: String, o: JSONObject, frameLib: Map<String, IntArray>, source: String, n: Int,
        errors: MutableList<String>
    ): GlyphAnimation? {
        val where = "$source/$name"
        val role = o.optString("role", "").lowercase().ifBlank { null }
        val label = o.optString("label", name)
        val loop = o.optBoolean("loop", true)
        val loopCount = o.optInt("loopCount", 0).coerceAtLeast(0)
        val fps = o.optDouble("fps", 12.0).toFloat().coerceIn(1f, 60f)
        val brightness = (o.optDouble("brightness", 100.0).toFloat() / 100f).coerceIn(0f, 1f)
        val variants = HashMap<String, String>()
        o.optJSONObject("variants")?.let { v -> for (k in v.keys()) variants[k] = v.optString(k) }

        val type = o.optString("type", if (o.has("frames")) "frames" else "procedural").lowercase()
        return when (type) {
            "procedural" -> {
                val gen = o.optString("generator", "")
                if (gen.isBlank()) {
                    errors += "$where: procedural animation needs a \"generator\""
                    return null
                }
                if (gen !in ProceduralGenerators.NAMES) {
                    errors += "$where: unknown generator '$gen' (known: ${ProceduralGenerators.NAMES.joinToString()})"
                    return null
                }
                val params = HashMap<String, Any?>()
                o.optJSONObject("params")?.let { p -> for (k in p.keys()) params[k] = p.opt(k) }
                val duration = o.optLong("durationMs", 1500L).coerceIn(100L, 60_000L)
                ProceduralAnimation(name, label, role, loop, loopCount, fps, brightness, variants, gen,
                    Params(params), duration)
            }
            "frames" -> {
                val arr = o.optJSONArray("frames")
                if (arr == null || arr.length() == 0) {
                    errors += "$where: \"frames\" must be a non-empty array"
                    return null
                }
                val defaultDuration = (1000f / fps).toLong()
                val steps = ArrayList<FrameStep>()
                for (i in 0 until arr.length()) {
                    parseStep(arr.opt(i), frameLib, defaultDuration, n, "$where/frames[$i]", errors)
                        ?.let { steps += it }
                }
                if (steps.isEmpty()) {
                    errors += "$where: no valid frames"
                    return null
                }
                FramesAnimation(name, label, role, loop, loopCount, fps, brightness, variants, steps)
            }
            else -> {
                errors += "$where: unknown type '$type' (use \"frames\" or \"procedural\")"
                null
            }
        }
    }

    private fun parseStep(
        e: Any?, frameLib: Map<String, IntArray>, defaultDuration: Long, n: Int, where: String,
        errors: MutableList<String>
    ): FrameStep? {
        return when (e) {
            is String -> {
                val px = frameLib[e]
                if (px == null) {
                    errors += "$where: unknown frame '$e'"
                    null
                } else FrameStep(px, defaultDuration, 1f)
            }
            is JSONObject -> {
                val px = when {
                    e.has("frame") -> frameLib[e.optString("frame")].also {
                        if (it == null) errors += "$where: unknown frame '${e.optString("frame")}'"
                    }
                    e.has("rows") -> e.optJSONArray("rows")?.let { parseRows(it, n, where, errors) }
                    else -> {
                        errors += "$where: needs \"frame\" or \"rows\""
                        null
                    }
                } ?: return null
                val duration = e.optLong("duration", defaultDuration).coerceIn(10L, 60_000L)
                val brightness = (e.optDouble("brightness", 100.0).toFloat() / 100f).coerceIn(0f, 1f)
                FrameStep(px, duration, brightness)
            }
            else -> {
                errors += "$where: must be a frame name or an object"
                null
            }
        }
    }

    /** Rows of characters -> intensities. See tools/gen_assets.py for the character legend. */
    private fun parseRows(rows: JSONArray, n: Int, where: String, errors: MutableList<String>): IntArray {
        val px = IntArray(n * n)
        if (rows.length() != n) errors += "$where: expected $n rows, got ${rows.length()} (padding/cropping)"
        var reportedBadChar = false
        for (y in 0 until n) {
            val line = rows.optString(y, "")
            if (line.length != n && y == 0) errors += "$where: expected $n characters per row (padding/cropping)"
            for (x in 0 until n) {
                val ch = line.getOrNull(x) ?: ' '
                val v = charToIntensity(ch)
                if (v == null) {
                    if (!reportedBadChar) {
                        errors += "$where: unsupported character '$ch' (use 0-9 a-f . # or space)"
                        reportedBadChar = true
                    }
                    px[y * n + x] = 0
                } else px[y * n + x] = v
            }
        }
        return px
    }

    private fun charToIntensity(c: Char): Int? = when (c) {
        in '0'..'9' -> (c - '0') * 17
        in 'a'..'f' -> (10 + (c - 'a')) * 17
        in 'A'..'F' -> (10 + (c - 'A')) * 17
        ' ', '.' -> 0
        '#' -> 255
        else -> null
    }
}

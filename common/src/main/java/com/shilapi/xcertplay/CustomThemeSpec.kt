package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runtime custom theme (V1).
 *
 * 默认目录是 /data/data/<package>/files/themes/<theme-id>/theme.json，
 * 设置页的“自定义主题目录”可以改成 adb 可写的目录（例如 Android/data/<package>/files/themes）。
 * 旧版外部目录仍会作为兼容目录扫描。
 *
 * Only declarative content is supported: images, brushes, shapes, static text, data-bound
 * gauges, live porthole slots, visibility conditions and simple keyframes. No executable code.
 */
data class CustomThemeSpec(
    val id: String,
    val name: String,
    val description: String,
    val dir: File,
    val layers: List<CustomLayerSpec>,
    val animations: List<CustomAnimationSpec>,
    val rules: List<CustomRuleSpec> = emptyList(),
    val states: List<CustomStateSpec> = emptyList(),
    /** Optional one-shot intro: force a state for holdMs on attach, then transition to normal. */
    val intro: CustomIntroSpec? = null,
    /** Optional one-shot slide in/hold/slide out for one drawn layer. */
    val sequence: CustomSequenceSpec? = null,
)

data class CustomSequenceSpec(
    val targets: Set<String>,
    val delayMs: Long,
    val inMs: Long,
    val holdMs: Long,
    val outMs: Long,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val hideAfter: Boolean,
    val repeat: Int = 1,
) {
    val totalMs: Long get() = delayMs + repeat * (inMs + holdMs + outMs)
    val target: String get() = targets.firstOrNull() ?: ""
}

data class CustomIntroSpec(
    val stateId: String,
    val holdMs: Long,
)

/** Phase-4 rule: one comparison with hysteresis, held for forMs, then actions. */
data class CustomRuleCondition(
    val source: String,
    val op: String,
    val value: Float,
    val release: Float?,
    /** speedDelta only: look-back window, 100..10000 ms. */
    val windowMs: Long = 1000L,
)

data class CustomRuleAction(
    val type: String,
    val target: String,
    val periodMs: Long,
    val text: String? = null,
    val durationMs: Long = 0L,
    val amplitude: Float = 0.1f,
    /** setImage only: relative file name inside the theme directory. */
    val imageSrc: String? = null,
)

data class CustomRuleSpec(
    val id: String,
    /** First condition; kept for the single-condition call sites. */
    val condition: CustomRuleCondition,
    val forMs: Long,
    val cooldownMs: Long,
    val actions: List<CustomRuleAction>,
    /** All conditions; empty means just [condition]. */
    val conditions: List<CustomRuleCondition> = emptyList(),
    /** single / all / any. */
    val combine: String = "single",
    /** Invert the combined result (NOT of the whole condition). */
    val negate: Boolean = false,
) {
    val allConditions: List<CustomRuleCondition>
        get() = if (conditions.isEmpty()) listOf(condition) else conditions
}

/** Phase-5 state overlay. Only the set fields override the layer; absent fields inherit. */
/** Pure state rect so the state machine stays testable on the JVM. */
data class StateRect(val x: Float, val y: Float, val w: Float, val h: Float)

data class CustomStateOverride(
    val visible: Boolean? = null,
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    /** ARGB color override for text/shape/vector; null keeps the layer color. */
    val color: Int? = null,
    /** Absolute panel rect; null keeps the layer dst. Interpolates during a state transition. */
    val dst: StateRect? = null,
)

data class CustomStateSpec(
    val id: String,
    /** null = base/fallback state. */
    val condition: CustomRuleCondition?,
    val transitionMs: Long,
    val overrides: Map<String, CustomStateOverride>,
)

data class CustomThemeEntry(
    val id: String,
    val name: String,
    val description: String,
    val dir: File,
)

data class CustomCondition(
    val minSpeed: Float? = null,
    val maxSpeed: Float? = null,
    val minRpm: Float? = null,
    val maxRpm: Float? = null,
    val appleFullMap: Boolean? = null,
    val night: Boolean? = null,
    val minRange: Float? = null,
    val maxRange: Float? = null,
    val minOdo: Float? = null,
    val maxOdo: Float? = null,
    val navActive: Boolean? = null,
    val songPlaying: Boolean? = null,
    /** Local minutes since midnight; a range that wraps past midnight is supported. */
    val clockFrom: Int? = null,
    val clockTo: Int? = null,
    val minGear: Float? = null,
    val maxGear: Float? = null,
    val turnLeft: Boolean? = null,
    val turnRight: Boolean? = null,
    val hazard: Boolean? = null,
    val minAudioLevel: Float? = null,
    val maxAudioLevel: Float? = null,
    val minAudioBass: Float? = null,
    val maxAudioBass: Float? = null,
    val minBeat: Float? = null,
    val maxBeat: Float? = null,
) {
    fun matches(data: ThemeData): Boolean {
        // speed/rpm keep the pre-ThemeData behaviour: NaN counts as 0 so old themes do not
        // suddenly show/hide differently when the poller is unavailable.
        val speed = data.speedKmh.finiteOrZero()
        val rpm = data.rpm.finiteOrZero()
        if (minSpeed != null && speed < minSpeed) return false
        if (maxSpeed != null && speed > maxSpeed) return false
        if (minRpm != null && rpm < minRpm) return false
        if (maxRpm != null && rpm > maxRpm) return false
        if (appleFullMap != null && appleFullMap != data.appleFullMap) return false
        if (night != null && night != data.night) return false
        if (navActive != null && navActive != data.navActive) return false
        if (songPlaying != null && songPlaying != data.songTitle.isNotBlank()) return false
        // Availability data (range/odometer) is different from speed: "unknown" must not read
        // as 0, otherwise a low-range warning would fire on every informational screen.
        if (minRange != null && !(data.rangeKm.isFinite() && data.rangeKm >= minRange)) return false
        if (maxRange != null && !(data.rangeKm.isFinite() && data.rangeKm <= maxRange)) return false
        if (minOdo != null && !(data.odometerKm.isFinite() && data.odometerKm >= minOdo)) return false
        if (maxOdo != null && !(data.odometerKm.isFinite() && data.odometerKm <= maxOdo)) return false
        val gear = data.gear.toFloat()
        if (minGear != null && gear < minGear) return false
        if (maxGear != null && gear > maxGear) return false
        if (turnLeft != null && turnLeft != data.turnLeft) return false
        if (turnRight != null && turnRight != data.turnRight) return false
        if (hazard != null && hazard != data.hazard) return false
        if (minAudioLevel != null && data.audioLevel < minAudioLevel) return false
        if (maxAudioLevel != null && data.audioLevel > maxAudioLevel) return false
        if (minAudioBass != null && data.audioBass < minAudioBass) return false
        if (maxAudioBass != null && data.audioBass > maxAudioBass) return false
        if (minBeat != null && data.beatPulse < minBeat) return false
        if (maxBeat != null && data.beatPulse > maxBeat) return false
        if (clockFrom != null || clockTo != null) {
            val from = clockFrom ?: 0
            val to = clockTo ?: 1_439
            val now = data.clockMinutes
            val inRange = if (from <= to) now in from..to else now >= from || now <= to
            if (!inRange) return false
        }
        return true
    }
}

data class CustomPaintSpec(
    val kind: String,
    val colors: List<Int>,
    val from: FloatArray,
    val to: FloatArray,
    val center: FloatArray,
    val radius: Float,
)

/**
 * Continuous mapping from a numeric vehicle value to one visual property (phase 2 / L1b).
 *
 * `fraction = clamp((value - fromMin) / (fromMax - fromMin))` and `output = toMin + span * f`.
 * For `property = "color"` the colors are interpolated by `f` and `toMin/toMax` are unused (0/1).
 */
data class CustomBindSpec(
    val source: String,
    val property: String,
    val fromMin: Float,
    val fromMax: Float,
    val toMin: Float,
    val toMax: Float,
    val clamp: Boolean,
    val easeMs: Long,
    val colors: List<Int>,
)

data class CustomGaugeSpec(
    val data: String,
    val center: FloatArray,
    val radius: Float,
    val start: Float,
    val sweep: Float,
    val min: Float,
    val max: Float,
    val thickness: Float,
    val colors: List<Int>,
    val trackColor: Int,
    val ticks: Int,
    val labels: List<Float>,
    val labelColor: Int,
    val labelSize: Float,
)

/** Polygon outline; [points] are dst-local pixels unless the layer says `normalized`. */
data class CustomStrokeSpec(
    val color: Int,
    val width: Float,
    val cap: String,
    val join: String,
)

/** Data-driven fill of a polygon (vector.bind): direction + range + smoothing. */
data class CustomFillBindSpec(
    val source: String,
    val min: Float,
    val max: Float,
    val direction: String,
    val easeMs: Long,
    val center: FloatArray,
    val start: Float,
)

data class CustomLayerSpec(
    val id: String,
    val type: String,
    val z: Float,
    val rect: RectF,
    /** dst was present in JSON; only then does a live nav width/height become an explicit viewport. */
    val dstSpecified: Boolean,
    val alpha: Float,
    val fit: String,
    val src: File?,
    /** True for `src: "@cover"`; the layer draws the current CarPlay album artwork. */
    val coverArtwork: Boolean = false,
    val spectrumBands: Int = 16,
    val spectrumGap: Float = 4f,
    val spectrumDirection: String = "up",
    val slot: String,
    val scale: Float,
    val paint: CustomPaintSpec?,
    val gauge: CustomGaugeSpec?,
    val shape: String,
    val text: String,
    val textSize: Float,
    val textColor: Int,
    val condition: CustomCondition?,
    /** Optional alternate image layer id for the speed/rpm "dynamic gif" rule. */
    val swapImageId: String = "",
    val swapMinSpeed: Float? = null,
    val swapMaxSpeed: Float? = null,
    val swapMinRpm: Float? = null,
    val swapMaxRpm: Float? = null,
    /** Resolved source file of [swapImageId] (null when missing/not an image). */
    val swapSrc: File? = null,
    /** Text bind template with `{source}` placeholders; empty means draw [text] as before. */
    val textTemplate: String = "",
    /** Drawn when [textTemplate] exists but a source has no value; defaults to [text]. */
    val textFallback: String = "",
    /** Minimum interval between template expansions (0 = every frame). */
    val textThrottleMs: Long = 0L,
    /** Numeric value -> visual property mapping; null when the layer has no `bind` object. */
    val bindSpec: CustomBindSpec? = null,
    /** vector polygon points (dst-local or 0..1 when [pointsNormalized]). */
    val points: List<FloatArray> = emptyList(),
    val pointsNormalized: Boolean = false,
    val fillRule: String = "nonzero",
    val stroke: CustomStrokeSpec? = null,
    /** vector `bind`: speed/rpm driven fill; null for a static polygon. */
    val fillBind: CustomFillBindSpec? = null,
)

data class CustomAnimationSpec(
    val target: String,
    val property: String,
    val durationMs: Long,
    val loop: Boolean,
    val keyframes: List<Pair<Float, Float>>,
)

object CustomThemeRepository {
    private const val TAG = "DiPlay-CustomTheme"
    private const val FILE = "theme.json"
    private val BIND_SOURCES = setOf("speed", "rpm", "odo", "range")
    private val BIND_PROPERTIES = setOf("alpha", "scale", "rotation", "x", "y", "color")
    private val RULE_SOURCES = setOf(
        "speed", "rpm", "odo", "range", "night", "appleFullMap", "navActive", "songPlaying",
        "speedDelta",
    )
    private val RULE_OPS = setOf("gt", "gte", "lt", "lte", "eq")
    private val RULE_ACTIONS = setOf("show", "hide", "flash", "pulse", "setText", "setImage")

    /** 车载默认目录。车机固定使用 /data/data，不要改成 /data/user/0。 */
    fun defaultThemesDirPath(context: Context): String =
        "/data/data/${context.packageName}/files/themes/"

    /**
     * 把 /data/data/<包名>/... 或 /data/user/0/<包名>/... 映射到 App 自己的 filesDir，
     * 避免不同 ROM 对这两个别名路径的权限差异；其它绝对路径按用户输入原样使用。
     */
    private fun resolveThemesDir(context: Context, raw: String): File? {
        val path = raw.trim().trimEnd('/')
        if (path.isEmpty()) return File(context.filesDir, "themes")
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        for (root in arrayOf("/data/data/${context.packageName}", "/data/user/0/${context.packageName}")) {
            if (path == root) return dataRoot
            if (path.startsWith("$root/")) {
                return File(dataRoot, path.substring(root.length + 1))
            }
        }
        // Android 11+ 对 /sdcard 和 /storage/emulated/0 的原始路径有额外的 FUSE 检查。
        // 统一映射到 App 自己的 getExternalFilesDir()，避免“路径存在但 listFiles() 返回空”。
        val externalFiles = context.getExternalFilesDir(null)
        val externalPackageRoot = externalFiles?.parentFile
        if (externalPackageRoot != null) {
            val relativePath = path.trimEnd('/')
            val roots = arrayOf(
                "/sdcard/Android/data/${context.packageName}",
                "/storage/emulated/0/Android/data/${context.packageName}",
                externalPackageRoot.absolutePath.trimEnd('/'),
            )
            for (root in roots) {
                if (root.isBlank()) continue
                if (relativePath == root) return externalPackageRoot
                if (relativePath.startsWith("$root/")) {
                    return File(externalPackageRoot, relativePath.substring(root.length).trimStart('/'))
                }
            }
        }
        if (!path.startsWith("/")) return null
        return File(path)
    }

    /** 当前展示路径；未改过时显示车机默认的 /data/data/<包名>/files/themes/。 */
    fun themesDirPath(context: Context): String {
        val configured = AirPlayPersistence.loadClusterThemeDir(context).trim().trimEnd('/')
        return if (configured.isEmpty()) defaultThemesDirPath(context) else "$configured/"
    }

    /** 当前主题目录；未改过或配置无效时回退到 App 自己的 files/themes。 */
    fun themesDir(context: Context): File {
        val configured = AirPlayPersistence.loadClusterThemeDir(context)
        val dir = try {
            resolveThemesDir(context, configured) ?: File(context.filesDir, "themes")
        } catch (e: Throwable) {
            Log.w(TAG, "theme dir resolve failed: ${e.message}", e)
            File(context.filesDir, "themes")
        }
        // A whitelisted/system install can point at an Android/data directory the process may
        // no longer write. mkdirs()/exists() can then throw SecurityException; porthole startup
        // calls PortholeTheme.load -> CustomThemeRepository.exists before any UI is ready, so an
        // uncaught permission error here is a process crash. Fall back to the app-private dir.
        try {
            if (!dir.exists()) dir.mkdirs()
            if (dir.isDirectory) return dir
        } catch (e: Throwable) {
            Log.w(TAG, "theme dir ${dir.absolutePath} unavailable: ${e.message}", e)
        }
        val fallback = File(context.filesDir, "themes")
        runCatching { if (!fallback.exists()) fallback.mkdirs() }
        return fallback
    }

    /** 校验并创建用户输入的主题目录；空路径表示恢复默认目录。 */
    fun validateThemesDir(context: Context, path: String): File? {
        val raw = path.trim()
        if (raw.isNotEmpty() && !raw.startsWith("/")) return null
        val dir = resolveThemesDir(context, raw) ?: return null
        if (dir.exists() && !dir.isDirectory) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        return if (dir.isDirectory && dir.canWrite()) dir else null
    }

    /**
     * Exactly one theme root: the app's default files/themes, or the folder the user configured.
     *
     * The old scan also picked up files/logs/themes and Android/data/<package>/files/themes.
     * The PC theme tool pushes to the default path only, so those implicit extra roots made the
     * in-car picker show themes the PC list could not see (and made deleting a theme look
     * ineffective when a stale copy lived in a legacy root). Pointing the setting at the
     * external folder remains supported; it becomes the single configured root.
     */
    private fun themesDirs(context: Context): List<File> = listOf(themesDir(context))

    /** Bench/test helper: copy the bundled sample theme into the log-adjacent theme folder. */
    fun seedSample(context: Context, assetDir: String = "test_theme", id: String = "test_theme"): Boolean {
        return try {
            val root = themesDir(context)
            val target = File(root, id).apply { mkdirs() }
            val names = context.assets.list(assetDir) ?: return false
            for (name in names) {
                val out = File(target, name)
                context.assets.open("$assetDir/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            Log.i(TAG, "seeded sample theme $id at ${target.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "seed sample failed", e)
            false
        }
    }

    fun all(context: Context): List<CustomThemeEntry> {
        val out = ArrayList<CustomThemeEntry>()
        for (root in themesDirs(context)) {
            val dirs = root.listFiles()
            if (dirs == null) {
                Log.w(TAG, "cannot list theme dir: $root")
                continue
            }
            for (dir in dirs.filter { it.isDirectory }.sortedBy { it.name.lowercase() }) {
                val entry = readEntry(dir)
                if (entry == null) {
                    Log.w(TAG, "ignored theme dir: ${dir.absolutePath}")
                    continue
                }
                if (out.none { it.id == entry.id }) out += entry
            }
        }
        return out
    }

    fun find(context: Context, id: String?): CustomThemeEntry? =
        if (id.isNullOrBlank()) null else all(context).firstOrNull { it.id == id }

    fun exists(context: Context, id: String?): Boolean = find(context, id) != null

    fun loadSpec(context: Context, id: String?): CustomThemeSpec? {
        val entry = find(context, id) ?: return null
        return try {
            parse(entry)
        } catch (e: Throwable) {
            Log.w(TAG, "load ${entry.id} failed: ${e.message}", e)
            null
        }
    }

    /** Built-in themes first, then custom themes in folder-name order. */
    fun selectionIds(context: Context): List<String> =
        PortholeTheme.entries.filter { it != PortholeTheme.CUSTOM }.map { it.id } +
            all(context).map { it.id }

    fun nextSelectionId(context: Context, currentId: String, step: Int): String {
        val ids = selectionIds(context)
        if (ids.isEmpty()) return PortholeTheme.DEFAULT.id
        val index = ids.indexOf(currentId).let { if (it < 0) 0 else it }
        return ids[((index + step) % ids.size + ids.size) % ids.size]
    }

    private fun readEntry(dir: File): CustomThemeEntry? {
        val file = File(dir, FILE)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(readJsonText(file))
            val id = json.optString("id", dir.name).trim().ifBlank { dir.name }
            val name = json.optString("name", id).trim().ifBlank { id }
            val description = json.optString("description", "").trim()
            CustomThemeEntry(id, name, description, dir)
        } catch (e: Throwable) {
            Log.w(TAG, "metadata ${dir.name} ignored: ${e.message}")
            null
        }
    }

    /**
     * Theme JSON written by other editors on Windows often starts with a UTF-8 BOM. org.json does
     * not skip it and throws on the first character, which used to make a valid theme "not exist"
     * on the device (and shows up in tools/theme_audit.py as a parse failure).
     */
    private fun readJsonText(file: File): String =
        file.readText(Charsets.UTF_8).removePrefix("\uFEFF")

    private fun parse(entry: CustomThemeEntry): CustomThemeSpec {
        val json = JSONObject(readJsonText(File(entry.dir, FILE)))
        val id = json.optString("id", entry.id).trim().ifBlank { entry.id }
        val name = json.optString("name", entry.name).trim().ifBlank { entry.name }
        val description = json.optString("description", entry.description).trim()
        val animations = parseAnimations(json.optJSONArray("animations"))
        val layersJson = json.optJSONArray("layers") ?: JSONArray()
        val layers = ArrayList<CustomLayerSpec>()
        for (i in 0 until layersJson.length()) {
            val obj = layersJson.optJSONObject(i) ?: continue
            layers += parseLayer(entry.dir, obj, i)
        }
        val resolvedLayers = layers.map { layer ->
            if (layer.type == "image" && layer.swapImageId.isNotBlank() &&
                layer.swapImageId != layer.id
            ) {
                val source = layers.firstOrNull {
                    it.type == "image" && it.id == layer.swapImageId && it.src != null
                }
                if (source?.src != null) layer.copy(swapSrc = source.src) else layer
            } else {
                layer
            }
        }
        val states = parseStates(json.optJSONArray("states"), resolvedLayers)
        return CustomThemeSpec(
            id, name, description, entry.dir, resolvedLayers, animations,
            rules = parseRules(json.optJSONArray("rules")),
            states = states,
            intro = parseIntro(json.optJSONObject("intro"), states),
            sequence = parseSequence(json.optJSONObject("sequence"), resolvedLayers),
        )
    }

    /** Test hook: keep the lower-case/camel-case source and action mapping under regression. */
    internal fun parseRulesForTest(arr: JSONArray): List<CustomRuleSpec> = parseRules(arr)

    internal fun canonicalRuleSource(raw: String): String? =
        RULE_SOURCES.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }

    internal fun canonicalRuleAction(raw: String): String? =
        RULE_ACTIONS.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }

    private data class ParsedWhen(
        val conditions: List<CustomRuleCondition>,
        val combine: String,
        val negate: Boolean,
    )

    private fun parseRules(arr: JSONArray?): List<CustomRuleSpec> {
        if (arr == null) return emptyList()
        val rules = ArrayList<CustomRuleSpec>()
        for (i in 0 until minOf(arr.length(), 32)) {
            val obj = arr.optJSONObject(i) ?: continue
            val whenObj = obj.optJSONObject("when") ?: continue
            val parsed = parseWhen(whenObj) ?: continue
            val actions = ArrayList<CustomRuleAction>()
            val actionsJson = obj.optJSONArray("actions") ?: JSONArray()
            for (j in 0 until minOf(actionsJson.length(), 8)) {
                val a = actionsJson.optJSONObject(j) ?: continue
                val type = canonicalRuleAction(a.optString("type", "")) ?: continue
                val target = a.optString("target", "").trim()
                if (target.isEmpty()) continue
                val text = if (type == "setText") a.optString("text", "").trim() else null
                if (type == "setText" && text.isNullOrEmpty()) continue
                val imageSrc = if (type == "setImage") {
                    val raw = a.optString("src", "").trim().replace('\\', '/')
                    if (raw.isEmpty() || raw.contains("..") || raw.contains('/')) continue
                    raw
                } else null
                actions += CustomRuleAction(
                    type = type,
                    target = target,
                    periodMs = a.optLong("periodMs", 500L).coerceIn(100L, 5_000L),
                    text = text,
                    durationMs = a.optLong("durationMs", 0L).coerceIn(0L, 600_000L),
                    amplitude = a.optDouble("amplitude", 0.1).toFloat().coerceIn(0f, 0.5f),
                    imageSrc = imageSrc,
                )
            }
            if (actions.isEmpty()) continue
            rules += CustomRuleSpec(
                id = obj.optString("id", "rule-$i").trim().ifBlank { "rule-$i" },
                condition = parsed.conditions.first(),
                forMs = obj.optLong("forMs", 0L).coerceIn(0L, 600_000L),
                cooldownMs = obj.optLong("cooldownMs", 0L).coerceIn(0L, 3_600_000L),
                actions = actions,
                conditions = parsed.conditions,
                combine = parsed.combine,
                negate = parsed.negate,
            )
        }
        return rules
    }

    /** One-level combinators: leaf, {all:[leaf...]}, {any:[leaf...]}, or {not: <above>}. */
    private fun parseWhen(whenObj: JSONObject): ParsedWhen? {
        val notObj = whenObj.optJSONObject("not")
        if (notObj != null) {
            val inner = parseWhen(notObj) ?: return null
            return ParsedWhen(inner.conditions, inner.combine, !inner.negate)
        }
        val all = whenObj.optJSONArray("all")
        val any = whenObj.optJSONArray("any")
        if (all != null || any != null) {
            val array = all ?: any ?: return null
            if (array.length() !in 1..3) return null
            val conditions = ArrayList<CustomRuleCondition>()
            for (i in 0 until array.length()) {
                val child = array.optJSONObject(i) ?: return null
                if (child.has("all") || child.has("any") || child.has("not")) return null
                conditions += parseLeaf(child) ?: return null
            }
            return ParsedWhen(conditions, if (all != null) "all" else "any", false)
        }
        val leaf = parseLeaf(whenObj) ?: return null
        return ParsedWhen(listOf(leaf), "single", false)
    }

    private fun parseIntro(obj: JSONObject?, states: List<CustomStateSpec>): CustomIntroSpec? {
        if (obj == null) return null
        val stateId = obj.optString("state", "").trim()
        if (stateId.isEmpty() || states.none { it.id == stateId }) return null
        return CustomIntroSpec(stateId, obj.optLong("holdMs", 800L).coerceIn(0L, 5000L))
    }

    private fun parseSequence(obj: JSONObject?, layers: List<CustomLayerSpec>): CustomSequenceSpec? {
        if (obj == null) return null
        val ids = layers.map { it.id }.toHashSet()
        val targets = LinkedHashSet<String>()
        obj.optString("target", "").trim().takeIf { it in ids }?.let { targets += it }
        val array = obj.optJSONArray("targets")
        if (array != null) {
            for (i in 0 until array.length()) {
                val id = array.optString(i, "").trim()
                if (id in ids) targets += id
            }
        }
        if (targets.isEmpty()) return null
        val delayMs = obj.optLong("delayMs", 0L).coerceIn(0L, 10000L)
        val inMs = obj.optLong("inMs", 300L).coerceIn(0L, 5000L)
        val holdMs = obj.optLong("holdMs", 800L).coerceIn(0L, 10000L)
        val outMs = obj.optLong("outMs", 300L).coerceIn(0L, 5000L)
        return CustomSequenceSpec(
            targets = targets,
            delayMs = delayMs,
            inMs = inMs,
            holdMs = holdMs,
            outMs = outMs,
            fromX = obj.optDouble("fromX", -600.0).toFloat().coerceIn(-2000f, 2000f),
            fromY = obj.optDouble("fromY", 0.0).toFloat().coerceIn(-2000f, 2000f),
            toX = obj.optDouble("toX", 0.0).toFloat().coerceIn(-2000f, 2000f),
            toY = obj.optDouble("toY", 0.0).toFloat().coerceIn(-2000f, 2000f),
            hideAfter = obj.optBoolean("hideAfter", false),
            repeat = obj.optInt("repeat", 1).coerceIn(1, 5),
        )
    }

    private fun parseStates(arr: JSONArray?, layers: List<CustomLayerSpec>): List<CustomStateSpec> {
        if (arr == null) return emptyList()
        val layerIds = layers.map { it.id }.toHashSet()
        val states = ArrayList<CustomStateSpec>()
        val ids = HashSet<String>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "state-$i").trim().ifBlank { "state-$i" }
            if (!ids.add(id)) continue
            val condition = obj.optJSONObject("when")?.let { parseLeaf(it) }
            val overrides = LinkedHashMap<String, CustomStateOverride>()
            val overridesJson = obj.optJSONObject("overrides")
            if (overridesJson != null) {
                val keys = overridesJson.keys()
                while (keys.hasNext()) {
                    val layerId = keys.next()
                    if (layerId !in layerIds) continue
                    val o = overridesJson.optJSONObject(layerId) ?: continue
                    val color = if (o.has("color")) {
                        runCatching { android.graphics.Color.parseColor(o.optString("color")) }.getOrNull()
                    } else null
                    val dst = o.optJSONArray("dst")?.let { array ->
                        if (array.length() < 4) null
                        else {
                            val x = array.optDouble(0, 0.0).toFloat()
                            val y = array.optDouble(1, 0.0).toFloat()
                            val w = array.optDouble(2, 0.0).toFloat()
                            val h = array.optDouble(3, 0.0).toFloat()
                            if (w <= 0f || h <= 0f) null
                            else StateRect(x, y, w, h)
                        }
                    }
                    overrides[layerId] = CustomStateOverride(
                        visible = if (o.has("visible")) o.optBoolean("visible") else null,
                        alpha = o.optDouble("alpha", 1.0).toFloat().coerceIn(0f, 1f),
                        scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.5f, 2f),
                        x = o.optDouble("x", 0.0).toFloat().coerceIn(-2000f, 2000f),
                        y = o.optDouble("y", 0.0).toFloat().coerceIn(-2000f, 2000f),
                        color = color,
                        dst = dst,
                    )
                }
            }
            states += CustomStateSpec(
                id = id,
                condition = condition,
                transitionMs = obj.optLong("transitionMs", 300L).coerceIn(0L, 5_000L),
                overrides = overrides,
            )
        }
        return states
    }

    private fun parseLeaf(obj: JSONObject): CustomRuleCondition? {        val source = canonicalRuleSource(obj.optString("source", "")) ?: return null
        val op = obj.optString("op", "gt").lowercase()
        if (op !in RULE_OPS) return null
        val value = obj.optDouble("value", Double.NaN).toFloat()
        if (!value.isFinite()) return null
        val release = if (obj.has("release")) obj.optDouble("release").toFloat() else null
        if (release != null && !release.isFinite()) return null
        val windowMs = obj.optLong("windowMs", 1000L).coerceIn(100L, 10_000L)
        return CustomRuleCondition(source, op, value, release, windowMs)
    }

    private fun parseLayer(dir: File, obj: JSONObject, index: Int): CustomLayerSpec {
        val id = obj.optString("id", "layer-$index")
        val type = obj.optString("type", "image").lowercase()
        val vector = type == "vector"
        val dst = obj.optJSONArray("dst")
        val dstSpecified = dst != null && dst.length() >= 4
        val rect = if (dst != null && dst.length() >= 4) {
            val left = dst.optDouble(0, 0.0).toFloat()
            val top = dst.optDouble(1, 0.0).toFloat()
            val width = dst.optDouble(2, 1920.0).toFloat()
            val height = dst.optDouble(3, 720.0).toFloat()
            RectF(left, top, left + width, top + height)
        } else {
            RectF(0f, 0f, 1920f, 720f)
        }
        val srcName = obj.optString("src", "").trim()
        val coverArtwork = type == "image" && srcName.equals("@cover", ignoreCase = true)
        val src = if (coverArtwork || srcName.isBlank()) null else safeAsset(dir, srcName)
        val paint = obj.optJSONObject("paint")?.let { parsePaint(it) }
        val gauge = obj.optJSONObject("gauge")?.let { parseGauge(it) }
        val condition = obj.optJSONObject("visibleIf")?.let { parseCondition(it) }
        val swap = obj.optJSONObject("swap")
        val swapSpeed = swap?.optJSONObject("speed")
        val swapRpm = swap?.optJSONObject("rpm")
        val staticText = obj.optString("text", "")
        val bind = obj.optJSONObject("bind")
        val textTemplate =
            if (type == "text") bind?.optString("template", "")?.trim().orEmpty() else ""
        val textFallback = if (textTemplate.isEmpty()) {
            staticText
        } else {
            bind?.optString("fallback", staticText)?.ifBlank { staticText } ?: staticText
        }
        val textThrottleMs = if (textTemplate.isEmpty()) {
            0L
        } else {
            (bind?.optLong("throttleMs", 0L) ?: 0L).coerceIn(0L, 5_000L)
        }
        val bindSpec = if (textTemplate.isEmpty() && !vector) parseBind(bind) else null
        val points = if (vector) parseVectorPoints(obj.optJSONArray("points")) else emptyList()
        val pointsNormalized = vector && obj.optBoolean("normalized", false)
        val fillRule = if (vector) obj.optString("fillRule", "nonzero").lowercase() else "nonzero"
        val stroke = if (vector) obj.optJSONObject("stroke")?.let { parseStroke(it) } else null
        val fillBind = if (vector) parseFillBind(bind) else null
        return CustomLayerSpec(
            id = id,
            type = type,
            z = obj.optDouble("z", (index * 10).toDouble()).toFloat(),
            rect = rect,
            dstSpecified = dstSpecified,
            alpha = obj.optDouble("alpha", 1.0).toFloat().coerceIn(0f, 1f),
            fit = obj.optString("fit", "stretch").lowercase(),
            src = src,
            coverArtwork = coverArtwork,
            spectrumBands = obj.optInt("bands", 16).coerceIn(4, 32),
            spectrumGap = obj.optDouble("gap", 4.0).toFloat().coerceIn(0f, 40f),
            spectrumDirection = obj.optString("direction", "up").lowercase(),
            slot = obj.optString("slot", "").lowercase(),
            scale = obj.optDouble("scale", 1.0).toFloat().coerceIn(0.05f, 5f),
            paint = paint,
            gauge = gauge,
            shape = if (vector) "polygon" else obj.optString("shape", "rect").lowercase(),
            text = obj.optString("text", ""),
            textSize = obj.optDouble("fontSize", 24.0).toFloat(),
            textColor = parseColor(obj.optString("color", "#FFFFFFFF"), Color.WHITE),
            condition = condition,
            swapImageId = swap?.optString("image", "")?.trim().orEmpty(),
            swapMinSpeed = if (swapSpeed?.has("gte") == true) swapSpeed.optDouble("gte").toFloat() else null,
            swapMaxSpeed = if (swapSpeed?.has("lte") == true) swapSpeed.optDouble("lte").toFloat() else null,
            swapMinRpm = if (swapRpm?.has("gte") == true) swapRpm.optDouble("gte").toFloat() else null,
            swapMaxRpm = if (swapRpm?.has("lte") == true) swapRpm.optDouble("lte").toFloat() else null,
            textTemplate = textTemplate,
            textFallback = textFallback,
            textThrottleMs = textThrottleMs,
            bindSpec = bindSpec,
            points = points,
            pointsNormalized = pointsNormalized,
            fillRule = fillRule,
            stroke = stroke,
            fillBind = fillBind,
        )
    }

    private fun parseVectorPoints(obj: JSONArray?): List<FloatArray> {
        if (obj == null) return emptyList()
        val points = ArrayList<FloatArray>(obj.length())
        for (i in 0 until minOf(obj.length(), 1024)) {
            val p = obj.optJSONArray(i) ?: continue
            if (p.length() < 2) continue
            val x = p.optDouble(0, Double.NaN).toFloat()
            val y = p.optDouble(1, Double.NaN).toFloat()
            if (x.isFinite() && y.isFinite()) points += floatArrayOf(x, y)
        }
        return points
    }

    private fun parseStroke(obj: JSONObject): CustomStrokeSpec {
        val cap = obj.optString("cap", "round").lowercase()
        val join = obj.optString("join", "round").lowercase()
        return CustomStrokeSpec(
            color = parseColor(obj.optString("color", "#7FE7FF"), Color.WHITE),
            width = obj.optDouble("width", 2.0).toFloat().coerceIn(0f, 64f),
            cap = if (cap in setOf("butt", "round", "square")) cap else "round",
            join = if (join in setOf("miter", "round", "bevel")) join else "round",
        )
    }

    private fun parseFillBind(obj: JSONObject?): CustomFillBindSpec? {
        if (obj == null) return null
        val source = obj.optString("source", "speed").lowercase()
        if (source != "speed" && source != "rpm") return null
        val range = obj.optJSONArray("range") ?: return null
        if (range.length() < 2) return null
        val min = range.optDouble(0, Double.NaN).toFloat()
        val max = range.optDouble(1, Double.NaN).toFloat()
        if (!min.isFinite() || !max.isFinite() || max <= min) return null
        val direction = obj.optString("direction", "up").lowercase()
        if (direction !in setOf("up", "down", "left", "right", "radial", "sweep")) return null
        val center = obj.optJSONArray("center")
        val cx = if (center != null && center.length() >= 2)
            center.optDouble(0, 0.5).toFloat().coerceIn(0f, 1f) else 0.5f
        val cy = if (center != null && center.length() >= 2)
            center.optDouble(1, 0.5).toFloat().coerceIn(0f, 1f) else 0.5f
        return CustomFillBindSpec(
            source = source,
            min = min,
            max = max,
            direction = direction,
            easeMs = obj.optLong("ease", 120L).coerceIn(0L, 2_000L),
            center = floatArrayOf(cx, cy),
            start = obj.optDouble("start", -90.0).toFloat(),
        )
    }

    private fun parsePaint(obj: JSONObject): CustomPaintSpec {
        val colorsJson = obj.optJSONArray("stops") ?: obj.optJSONArray("colors")
        val colors = ArrayList<Int>()
        if (colorsJson != null) {
            for (i in 0 until colorsJson.length()) {
                val item = colorsJson.opt(i)
                when (item) {
                    is JSONArray -> colors += parseColor(item.optString(1, "#FFFFFFFF"), Color.WHITE)
                    is String -> colors += parseColor(item, Color.WHITE)
                    is JSONObject -> colors += parseColor(item.optString("color", "#FFFFFFFF"), Color.WHITE)
                }
            }
        }
        if (colors.isEmpty()) colors += Color.TRANSPARENT
        fun pair(name: String, dx: Float, dy: Float): FloatArray {
            val a = obj.optJSONArray(name)
            return if (a != null && a.length() >= 2) {
                floatArrayOf(a.optDouble(0, dx.toDouble()).toFloat(), a.optDouble(1, dy.toDouble()).toFloat())
            } else {
                floatArrayOf(dx, dy)
            }
        }
        val center = pair("center", 960f, 360f)
        return CustomPaintSpec(
            kind = obj.optString("kind", "linear").lowercase(),
            colors = colors,
            from = pair("from", 0f, 0f),
            to = pair("to", 0f, 720f),
            center = center,
            radius = obj.optDouble("radius", 700.0).toFloat(),
        )
    }

    private fun parseGauge(obj: JSONObject): CustomGaugeSpec {
        fun pair(name: String, dx: Float, dy: Float): FloatArray {
            val a = obj.optJSONArray(name)
            return if (a != null && a.length() >= 2) {
                floatArrayOf(a.optDouble(0, dx.toDouble()).toFloat(), a.optDouble(1, dy.toDouble()).toFloat())
            } else {
                floatArrayOf(dx, dy)
            }
        }
        val colorsArr = obj.optJSONArray("colors")
        val colors = ArrayList<Int>()
        if (colorsArr != null) for (i in 0 until colorsArr.length()) {
            colors += parseColor(colorsArr.optString(i, "#FFFFFFFF"), Color.WHITE)
        }
        if (colors.isEmpty()) colors += Color.WHITE
        val labelsArr = obj.optJSONArray("labels")
        val labels = ArrayList<Float>()
        if (labelsArr != null) for (i in 0 until labelsArr.length()) labels += labelsArr.optDouble(i, 0.0).toFloat()
        return CustomGaugeSpec(
            data = obj.optString("data", "speed").lowercase(),
            center = pair("center", 255f, 330f),
            radius = obj.optDouble("radius", 190.0).toFloat(),
            start = obj.optDouble("start", 120.0).toFloat(),
            sweep = obj.optDouble("sweep", 300.0).toFloat(),
            min = obj.optDouble("min", 0.0).toFloat(),
            max = obj.optDouble("max", 220.0).toFloat(),
            thickness = obj.optDouble("thickness", 20.0).toFloat(),
            colors = colors,
            trackColor = parseColor(obj.optString("track", "#33FFFFFF"), Color.argb(40, 255, 255, 255)),
            ticks = obj.optInt("ticks", 24),
            labels = labels,
            labelColor = parseColor(obj.optString("labelColor", "#FFFFFFFF"), Color.WHITE),
            labelSize = obj.optDouble("labelSize", 18.0).toFloat(),
        )
    }

    /**
     * `bind` for non-text layers: numeric value -> one visual property. Invalid specs are ignored
     * with a log line instead of failing the whole theme, so a typo only costs the effect.
     */
    private fun parseBind(obj: JSONObject?): CustomBindSpec? {
        if (obj == null) return null
        val source = obj.optString("source", "").trim().lowercase()
        if (source !in BIND_SOURCES) {
            if (source.isNotEmpty()) Log.w(TAG, "bind.source=$source ignored")
            return null
        }
        val property = obj.optString("property", "alpha").trim().lowercase()
        if (property !in BIND_PROPERTIES) {
            Log.w(TAG, "bind.property=$property ignored")
            return null
        }
        val from = obj.optJSONArray("from")
        if (from == null || from.length() < 2) {
            Log.w(TAG, "bind.from must be [min,max]")
            return null
        }
        val fromMin = from.optDouble(0).toFloat()
        val fromMax = from.optDouble(1).toFloat()
        if (!fromMin.isFinite() || !fromMax.isFinite() || fromMax <= fromMin) {
            Log.w(TAG, "bind.from invalid [$fromMin,$fromMax]")
            return null
        }
        var toMin = 0f
        var toMax = 1f
        val colors = ArrayList<Int>()
        if (property == "color") {
            val arr = obj.optJSONArray("colors")
            if (arr == null || arr.length() < 2) {
                Log.w(TAG, "bind.colors needs two colors for property=color")
                return null
            }
            colors += parseColor(arr.optString(0, "#FFFFFFFF"), Color.WHITE)
            colors += parseColor(arr.optString(1, "#FFFFFFFF"), Color.WHITE)
        } else {
            val to = obj.optJSONArray("to")
            if (to == null || to.length() < 2) {
                Log.w(TAG, "bind.to must be [min,max]")
                return null
            }
            toMin = to.optDouble(0).toFloat()
            toMax = to.optDouble(1).toFloat()
            if (!toMin.isFinite() || !toMax.isFinite()) {
                Log.w(TAG, "bind.to invalid")
                return null
            }
        }
        return CustomBindSpec(
            source = source,
            property = property,
            fromMin = fromMin,
            fromMax = fromMax,
            toMin = toMin,
            toMax = toMax,
            clamp = obj.optBoolean("clamp", true),
            easeMs = obj.optLong("ease", 120L).coerceIn(0L, 2_000L),
            colors = colors,
        )
    }

    private fun parseCondition(obj: JSONObject): CustomCondition {
        fun threshold(name: String, key: String): Float? {
            val value = obj.optJSONObject(name) ?: return null
            return if (value.has(key)) value.optDouble(key).toFloat() else null
        }
        fun flag(name: String): Boolean? =
            if (obj.has(name)) obj.optBoolean(name) else null
        fun clock(name: String): Int? {
            val value = obj.optJSONObject("clock") ?: return null
            return parseClockMinutes(value.optString(name, ""))
        }
        return CustomCondition(
            minSpeed = threshold("speed", "gte"),
            maxSpeed = threshold("speed", "lte"),
            minRpm = threshold("rpm", "gte"),
            maxRpm = threshold("rpm", "lte"),
            appleFullMap = flag("appleFullMap"),
            night = flag("night"),
            minRange = threshold("range", "gte"),
            maxRange = threshold("range", "lte"),
            minOdo = threshold("odo", "gte"),
            maxOdo = threshold("odo", "lte"),
            navActive = flag("navActive"),
            songPlaying = flag("songPlaying"),
            clockFrom = clock("gte"),
            clockTo = clock("lte"),
            minGear = threshold("gear", "gte"),
            maxGear = threshold("gear", "lte"),
            turnLeft = flag("turnLeft"),
            turnRight = flag("turnRight"),
            hazard = flag("hazard"),
            minAudioLevel = threshold("audioLevel", "gte"),
            maxAudioLevel = threshold("audioLevel", "lte"),
            minAudioBass = threshold("audioBass", "gte"),
            maxAudioBass = threshold("audioBass", "lte"),
            minBeat = threshold("beatPulse", "gte"),
            maxBeat = threshold("beatPulse", "lte"),
        )
    }

    /** "HH:mm" -> minutes since midnight; anything else is ignored by the parser. */
    private fun parseClockMinutes(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun parseAnimations(arr: JSONArray?): List<CustomAnimationSpec> {
        if (arr == null) return emptyList()
        val out = ArrayList<CustomAnimationSpec>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val keys = ArrayList<Pair<Float, Float>>()
            val kfs = obj.optJSONArray("keyframes")
            if (kfs != null) for (k in 0 until kfs.length()) {
                val pair = kfs.optJSONArray(k) ?: continue
                keys += pair.optDouble(0, 0.0).toFloat() to pair.optDouble(1, 0.0).toFloat()
            }
            if (keys.isEmpty()) continue
            out += CustomAnimationSpec(
                target = obj.optString("target", ""),
                property = obj.optString("property", "").lowercase(),
                durationMs = (obj.optDouble("duration", 2.0) * 1000.0).toLong().coerceAtLeast(50L),
                loop = obj.optBoolean("loop", true),
                keyframes = keys.sortedBy { it.first },
            )
        }
        return out
    }

    private fun safeAsset(dir: File, name: String): File? {
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) return null
        return try {
            val file = File(dir, name).canonicalFile
            val base = dir.canonicalFile
            if (file.path.startsWith(base.path + File.separator) && file.isFile) file else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseColor(value: String, fallback: Int): Int = try {
        Color.parseColor(value.trim())
    } catch (_: Throwable) {
        fallback
    }
}

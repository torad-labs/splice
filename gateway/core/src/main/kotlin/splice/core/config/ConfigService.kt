// PORT-OF: server/src/config.mjs layers/coerce/normalize/getConfig/configLayers/patchConfig
// @ 4ca99f7 — invariants: layers merge FRESH on every read (v29 froze knobs at import; nothing
// was hot-tunable); precedence defaults <- headOverrides(TOML, NEW layer) <- state config.json
// (mtime-cached) <- env (alias order) <- runtime PATCH; PATCH persists to the state file
// best-effort (env still wins at next boot); normalization floors (upstreamTimeout >= 30s,
// firstByte >= 10s, streamIdle >= 30s or 250ms under CODEX_PROXY_TEST=1, authCache >= 5s);
// showReasoning alias folding; trailing-slash strip on base urls; maxInflight >= 0.
// SEAM (recorded): env access is injected as a reader function so tests can fake the
// environment (the JVM cannot setenv); production passes System::getenv.
package splice.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path

public class ConfigService(
    private val statePaths: StatePaths,
    private val headOverrides: Map<String, String> = emptyMap(),
    private val envReader: (String) -> String? = System::getenv,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val runtimeLayer = LinkedHashMap<String, Any?>()

    @Volatile
    private var fileCache: Triple<Path, Long, Map<String, Any?>>? = null

    public fun getConfig(): SpliceConfig = SpliceConfig(normalize(mergedRaw()))

    public fun layers(): ConfigLayers = ConfigLayers(
        defaults = Knob.entries.associate { it.key to it.default },
        headOverrides = coerceAll(headOverrides),
        file = fileLayer(),
        env = envLayer(),
        runtime = runtimeLayer.toMap(),
    )

    // The guard cascade is the literal port of config.mjs's patch loop (each `when` arm is one of
    // its `continue`s): unknown key -> reject; null -> delete; uncoercible -> reject; else -> apply.
    public fun patch(partial: Map<String, Any?>): PatchResult {
        val applied = LinkedHashMap<String, Any?>()
        val rejected = LinkedHashMap<String, String>()
        for ((key, raw) in partial) {
            val knob = Knob.byKey[key]
            when {
                knob == null -> rejected[key] = "unknown key"
                raw == null -> {
                    applied[key] = null
                    runtimeLayer.remove(key)
                }
                else -> {
                    val coerced = coerce(knob, raw)
                    if (coerced == null) {
                        rejected[key] = "invalid value"
                    } else {
                        applied[key] = coerced
                        runtimeLayer[key] = coerced
                    }
                }
            }
        }
        if (applied.isNotEmpty()) persistApplied(applied)
        val restartRequired = applied.keys.filter { it in Knob.restartRequiredKeys }
        return PatchResult(applied, rejected, restartRequired, getConfig())
    }

    public fun resetRuntimeForTests() {
        runtimeLayer.clear()
        fileCache = null
    }

    private fun mergedRaw(): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        Knob.entries.forEach { merged[it.key] = it.default }
        coerceAll(headOverrides).forEach { (k, v) -> merged[k] = v }
        fileLayer().forEach { (k, v) -> merged[k] = v }
        envLayer().forEach { (k, v) -> merged[k] = v }
        runtimeLayer.forEach { (k, v) -> merged[k] = v }
        return merged
    }

    private fun coerceAll(raw: Map<String, String>): Map<String, Any?> =
        raw.entries.mapNotNull { (k, v) ->
            val knob = Knob.byKey[k] ?: return@mapNotNull null
            coerce(knob, v)?.let { k to it }
        }.toMap()

    // Best-effort by design (port fidelity): a broken/absent state file yields {} — the daemon
    // must never crash on config reads. Complexity is the flat coercion walk.
    private fun fileLayer(): Map<String, Any?> =
        runCatchingCancellable { readFileLayer() }.getOrDefault(emptyMap())

    private fun readFileLayer(): Map<String, Any?> {
        val path = statePaths.configFile
        if (!Files.exists(path)) return emptyMap()
        val mtime = Files.getLastModifiedTime(path).toMillis()
        fileCache?.let { (p, m, data) -> if (p == path && m == mtime) return data }
        val parsed = json.parseToJsonElement(Files.readString(path)).jsonObject
        val data = LinkedHashMap<String, Any?>()
        for (knob in Knob.entries) {
            fileScalar(parsed, knob)?.let { data[knob.key] = it }
        }
        fileCache = Triple(path, mtime, data)
        return data
    }

    private fun fileScalar(parsed: JsonObject, knob: Knob): Any? {
        val el = parsed[knob.key] ?: return null
        val scalar = jsonScalar(el) ?: return null
        return coerce(knob, scalar)
    }

    private fun envLayer(): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        for (knob in Knob.entries) {
            val raw = knob.envNames.firstNotNullOfOrNull { name -> envReader(name)?.takeIf { it.isNotEmpty() } }
            if (raw != null) coerce(knob, raw)?.let { data[knob.key] = it }
        }
        return data
    }

    // Best-effort by design (port fidelity): persistence failure must not undo the applied
    // runtime layer. Env still wins at next boot — the launcher is the boot authority.
    private fun persistApplied(applied: Map<String, Any?>) {
        // persistence is best-effort; the runtime layer already applied
        runCatchingCancellable {
            Files.createDirectories(statePaths.stateDir)
            val path = statePaths.configFile
            val onDisk = runCatchingCancellable { readOnDisk(path) }.getOrDefault(JsonObject(emptyMap()))
            val next = mergePersisted(onDisk, applied)
            Files.writeString(path, json.encodeToString(JsonObject.serializer(), next) + "\n")
            fileCache = null
        }
    }

    private fun readOnDisk(path: Path): JsonObject =
        if (Files.exists(path)) {
            json.parseToJsonElement(Files.readString(path)).jsonObject
        } else {
            JsonObject(emptyMap())
        }

    private fun mergePersisted(onDisk: JsonObject, applied: Map<String, Any?>): JsonObject =
        buildJsonObject {
            onDisk.forEach { (k, v) -> if (k !in applied) put(k, v) }
            applied.forEach { (k, v) ->
                when (v) {
                    null -> Unit // deletion = omission from the persisted file
                    is Boolean -> put(k, JsonPrimitive(v))
                    is Long -> put(k, JsonPrimitive(v))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }

    // A flat table of floors/clamps — splitting it would scatter the contract (port fidelity).
    private fun normalize(raw: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(raw)

        listOf(Knob.CHATGPT_API_BASE, Knob.XAI_API_BASE).forEach { k ->
            out[k.key] = str(out, k)?.trimEnd('/')
        }
        out[Knob.MAX_INFLIGHT.key] = clampLong(out, Knob.MAX_INFLIGHT, floor = 0L)
        out[Knob.UPSTREAM_RETRIES.key] = clampLong(out, Knob.UPSTREAM_RETRIES, floor = 1L, default = 2L)
        out[Knob.UPSTREAM_TIMEOUT_MS.key] = clampLong(out, Knob.UPSTREAM_TIMEOUT_MS, floor = MIN_UPSTREAM_TIMEOUT_MS)
        out[Knob.FIRST_BYTE_TIMEOUT_MS.key] = clampLong(out, Knob.FIRST_BYTE_TIMEOUT_MS, floor = MIN_FIRST_BYTE_MS)
        val idleFloor = if (envReader("CODEX_PROXY_TEST") == "1") TEST_IDLE_FLOOR_MS else MIN_STREAM_IDLE_MS
        out[Knob.STREAM_IDLE_MS.key] = clampLong(out, Knob.STREAM_IDLE_MS, floor = idleFloor)
        out[Knob.AUTH_CACHE_MS.key] = clampLong(out, Knob.AUTH_CACHE_MS, floor = MIN_AUTH_CACHE_MS)
        out[Knob.SHOW_REASONING.key] = normalizeShowReasoning(str(out, Knob.SHOW_REASONING))
        out[Knob.CONTEXT_WINDOW_OVERRIDE.key] = positiveLong(out, Knob.CONTEXT_WINDOW_OVERRIDE)
        out[Knob.USAGE_WARN_PCT.key] = (num(out, Knob.USAGE_WARN_PCT) ?: 0L).coerceIn(0L, 100L)
        out[Knob.USAGE_WARN_TOKENS_5H.key] = clampLong(out, Knob.USAGE_WARN_TOKENS_5H, floor = 0L)
        out[Knob.CONTROL_PORT.key] = positiveLong(out, Knob.CONTROL_PORT) ?: Knob.CONTROL_PORT.default
        return out
    }

    private companion object {
        const val MIN_UPSTREAM_TIMEOUT_MS = 30_000L
        const val MIN_FIRST_BYTE_MS = 10_000L
        const val MIN_STREAM_IDLE_MS = 30_000L
        const val TEST_IDLE_FLOOR_MS = 250L
        const val MIN_AUTH_CACHE_MS = 5_000L

        fun jsonScalar(el: JsonElement): Any? = when (el) {
            is JsonPrimitive -> el.booleanOrNull ?: el.longOrNull ?: el.content
            else -> null
        }

        fun coerce(knob: Knob, raw: Any?): Any? = when (knob.kind) {
            KnobKind.BOOL -> when (raw) {
                is Boolean -> raw
                else -> Regex("^(1|true|yes|on)$", RegexOption.IGNORE_CASE).matches(raw.toString().trim())
            }
            KnobKind.NUMBER -> {
                val s = raw.toString().trim().lowercase()
                if (knob == Knob.MAX_INFLIGHT && s in setOf("", "unlimited", "off", "none")) {
                    0L
                } else {
                    s.toDoubleOrNull()?.toLong()
                }
            }
            KnobKind.STRING -> raw.toString().trim().ifEmpty { null }
        }

        // Shared reads over the merged map — one place each so `normalize` stays a flat clamp table.
        fun str(out: Map<String, Any?>, k: Knob): String? = out[k.key]?.toString()

        fun num(out: Map<String, Any?>, k: Knob): Long? = (out[k.key] as? Long) ?: str(out, k)?.toLongOrNull()

        // Read [k] as a long, substitute [default] when absent, then apply the [floor].
        fun clampLong(out: Map<String, Any?>, k: Knob, floor: Long, default: Long = 0L): Long =
            maxOf(floor, num(out, k) ?: default)

        fun positiveLong(out: Map<String, Any?>, k: Knob): Long? = num(out, k)?.takeIf { it > 0 }
    }
}

public fun normalizeShowReasoning(raw: String?): String {
    val v = raw?.trim()?.lowercase().orEmpty()
    return when {
        v.isEmpty() || v in setOf("0", "false", "off", "none") -> "off"
        v in setOf("text", "mirror", "full", "both", "2") -> "text"
        else -> "thinking"
    }
}

/** Typed view over the merged+normalized map. */
public class SpliceConfig internal constructor(private val m: Map<String, Any?>) {
    public val port: Int get() = long(Knob.PORT).toInt()
    public val chatgptApiBase: String get() = string(Knob.CHATGPT_API_BASE).orEmpty()
    public val codexAuthPath: String get() = string(Knob.CODEX_AUTH_PATH).orEmpty()
    public val pinnedModel: String get() = string(Knob.PINNED_MODEL).orEmpty()
    public val compactModel: String? get() = string(Knob.COMPACT_MODEL)
    public val effort: String? get() = string(Knob.EFFORT)
    public val summary: String? get() = string(Knob.SUMMARY)
    public val showReasoning: String get() = string(Knob.SHOW_REASONING).orEmpty()
    public val replayReasoning: Boolean get() = bool(Knob.REPLAY_REASONING)
    public val maxInflight: Int get() = long(Knob.MAX_INFLIGHT).toInt()
    public val upstreamRetries: Int get() = long(Knob.UPSTREAM_RETRIES).toInt()
    public val upstreamTimeoutMs: Long get() = long(Knob.UPSTREAM_TIMEOUT_MS)
    public val firstByteTimeoutMs: Long get() = long(Knob.FIRST_BYTE_TIMEOUT_MS)
    public val streamIdleMs: Long get() = long(Knob.STREAM_IDLE_MS)
    public val authCacheMs: Long get() = long(Knob.AUTH_CACHE_MS)
    public val debug: Boolean get() = bool(Knob.DEBUG)
    public val contextWindowOverride: Long? get() = m[Knob.CONTEXT_WINDOW_OVERRIDE.key] as? Long
    public val grokPort: Int get() = long(Knob.GROK_PORT).toInt()
    public val grokModel: String get() = string(Knob.GROK_MODEL).orEmpty()
    public val xaiApiBase: String get() = string(Knob.XAI_API_BASE).orEmpty()
    public val grokAuthPath: String get() = string(Knob.GROK_AUTH_PATH).orEmpty()
    public val controlPort: Int get() = long(Knob.CONTROL_PORT).toInt()
    public val usageWarnPct: Int get() = long(Knob.USAGE_WARN_PCT).toInt()
    public val usageWarnTokens5h: Long get() = long(Knob.USAGE_WARN_TOKENS_5H)

    public fun asMap(): Map<String, Any?> = m

    private fun string(k: Knob): String? = m[k.key]?.toString()

    private fun long(k: Knob): Long = (m[k.key] as? Long) ?: m[k.key]?.toString()?.toLongOrNull() ?: 0L

    private fun bool(k: Knob): Boolean = m[k.key] == true
}

public data class ConfigLayers(
    val defaults: Map<String, Any?>,
    val headOverrides: Map<String, Any?>,
    val file: Map<String, Any?>,
    val env: Map<String, Any?>,
    val runtime: Map<String, Any?>,
)

public data class PatchResult(
    val applied: Map<String, Any?>,
    val rejected: Map<String, String>,
    val restartRequired: List<String>,
    val effective: SpliceConfig,
)

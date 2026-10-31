// PORT-OF: server/src/config.mjs coerce/normalize @ pre-public-port-baseline — the knob COERCION
// + NORMALIZATION half of the config engine. Invariants (moved here verbatim from
// ConfigService.kt's header, because this is where they are enforced): normalization floors
// (upstreamTimeout >= 30s, firstByte >= 10s, streamIdle >= 30s or 250ms under CODEX_PROXY_TEST=1,
// authCache >= 5s); showReasoning alias folding; trailing-slash strip on base urls;
// maxInflight >= 0; bool coercion is /^(1|true|yes|on)$/i for true and /^(0|false|no|off)$/i for
// false, anything else refused by name ([BoolKnobWords], V4-286).
// Split out of ConfigService.kt as a CLASS at HD-M8 (2026-08-16) because that class sat on the
// 14-function ceiling; given its own FILE at HD-25 (2026-08-18) because it was never ConfigService
// state — it holds zero, takes [EnvReader] in its constructor, and the shared file was the only
// thing coupling them.
// The clamp/floor constants travel WITH it and are not optional: `private` at file scope is
// file-private in Kotlin, so leaving them behind in ConfigService.kt would not compile.
package splice.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.memory.HeapWeights
import splice.core.perf.HistoryWindowWords
import splice.core.util.EnvReader
import java.util.Collections

private const val MIN_UPSTREAM_TIMEOUT_MS = 30_000L
private const val MIN_FIRST_BYTE_MS = 10_000L
private const val MIN_STREAM_IDLE_MS = 30_000L
private const val TEST_IDLE_FLOOR_MS = 250L
private const val MIN_AUTH_CACHE_MS = 5_000L

// why: an idle-close below one second would close a socket between a tool result and the next round
private const val MIN_WS_IDLE_CLOSE_MS = 1_000L

// why: the backend ends every socket at 60 minutes, so a pooled socket retires one minute before that
private const val MAX_WS_MAX_AGE_MS = 3_540_000L

// why: a poll faster than the five-minute cadence would hammer the provider's usage endpoint
private const val MIN_QUOTA_POLL_INTERVAL_MS = 30_000L
private const val MAX_PORT = 65_535L
private const val MAX_INT = Int.MAX_VALUE.toLong()

/** The words a BOOL knob reads, any case, around whitespace. Anything else is no value: every layer
 *  refuses it by name (ConfigService.coerceRejects, the env log line, PATCH's rejected map) and the knob
 *  keeps its default. V4-286: anything but a true word read as false, so `trace = "enabled"` turned a
 *  head's trace off in silence and the next start deleted its history. */
public object BoolKnobWords {
    private val truth = Regex("^(1|true|yes|on)$", RegexOption.IGNORE_CASE)
    private val falsity = Regex("^(0|false|no|off)$", RegexOption.IGNORE_CASE)

    /** True or false for a bool word, null for anything else. */
    public fun of(raw: String): Boolean? {
        val word = raw.trim()
        return when {
            truth.matches(word) -> true
            falsity.matches(word) -> false
            else -> null
        }
    }
}

/**
 * The knob COERCION + NORMALIZATION half of [ConfigService], split out because the class is at the
 * 14-function ceiling and cannot absorb the dissolved companion (Kotlin style law, 2026-08-16 —
 * HD-M8). [ConfigService] holds one instance and reaches them through it. `internal` because
 * nothing outside :core reads them.
 *
 * HD-25 (2026-08-18) moved it to this file and made two changes inside it, both behaviour-neutral:
 * the three constant matchers [coerce] used to rebuild on EVERY call are now properties, and
 * [coerce]'s three per-kind arms are named functions. Everything else is the same names and the
 * same bodies.
 */
internal class ConfigCoercion(private val envReader: EnvReader) {

    // HD-25: these were constructed INSIDE [coerce], so every call rebuilt a compiled Regex and two
    // sets — and coerce runs once per knob per layer, i.e. hundreds of times per merge, on a path that
    // merges FRESH on every read (this engine's founding invariant). They are constants with no
    // dependency on the arguments, so a property is their right home; the bool words are
    // [BoolKnobWords]'s since V4-286.
    private val unlimitedKnobs = setOf(Knob.MAX_INFLIGHT, Knob.MAX_QUEUED)
    private val unlimitedWords = setOf("", "unlimited", "off", "none")
    private val codeModeCounts =
        setOf(Knob.CODE_MODE_DEFAULT_EXEC_YIELD_TIME_MS, Knob.CODE_MODE_TOOL_INPUT_SCHEMA_MAX_BYTES)

    // A flat table of floors/clamps — splitting it would scatter the contract (port fidelity).
    fun normalize(
        raw: Map<String, Any?>,
        testIdleFloor: Boolean = envReader("CODEX_PROXY_TEST") == "1",
    ): Map<String, Any?> {
        val out = LinkedHashMap(raw)

        listOf(Knob.CHATGPT_API_BASE, Knob.XAI_API_BASE).forEach { k ->
            out[k.key] = str(out, k)?.trimEnd('/')
        }
        // clampLong's `default` only fires when the value is ABSENT (num returns null); an
        // explicit 0/negative port is PRESENT, so clampLong would still floor-clamp it to 1
        // (unbindable). Ports need pre-commit's `positiveLong ?: default` contract instead —
        // reject non-positive values outright, then clamp only the ceiling.
        out[Knob.PORT.key] =
            (positiveLong(out, Knob.PORT) ?: Knob.PORT.count()).coerceAtMost(MAX_PORT)
        out[Knob.GROK_PORT.key] =
            (positiveLong(out, Knob.GROK_PORT) ?: Knob.GROK_PORT.count()).coerceAtMost(MAX_PORT)
        out[Knob.CONTROL_PORT.key] =
            (positiveLong(out, Knob.CONTROL_PORT) ?: Knob.CONTROL_PORT.count()).coerceAtMost(MAX_PORT)
        out[Knob.MAX_INFLIGHT.key] = clampLong(out, Knob.MAX_INFLIGHT, floor = 0L, ceiling = MAX_INT)
        out[Knob.MAX_QUEUED.key] = clampLong(out, Knob.MAX_QUEUED, floor = 0L, ceiling = MAX_INT)
        out[Knob.UPSTREAM_TIMEOUT_MS.key] = clampLong(out, Knob.UPSTREAM_TIMEOUT_MS, floor = MIN_UPSTREAM_TIMEOUT_MS)
        out[Knob.FIRST_BYTE_TIMEOUT_MS.key] = clampLong(out, Knob.FIRST_BYTE_TIMEOUT_MS, floor = MIN_FIRST_BYTE_MS)
        val idleFloor = if (testIdleFloor) TEST_IDLE_FLOOR_MS else MIN_STREAM_IDLE_MS
        out[Knob.STREAM_IDLE_MS.key] = clampLong(out, Knob.STREAM_IDLE_MS, floor = idleFloor)
        out[Knob.AUTH_CACHE_MS.key] = clampLong(out, Knob.AUTH_CACHE_MS, floor = MIN_AUTH_CACHE_MS)
        out[Knob.WS_IDLE_CLOSE_MS.key] = clampLong(out, Knob.WS_IDLE_CLOSE_MS, floor = MIN_WS_IDLE_CLOSE_MS)
        out[Knob.WS_MAX_AGE_MS.key] =
            clampLong(out, Knob.WS_MAX_AGE_MS, floor = MIN_WS_IDLE_CLOSE_MS, ceiling = MAX_WS_MAX_AGE_MS)
        out[Knob.MIRROR_REASONING.key] = false
        // Summary is operator-controlled (TOML [daemon].summary / env / state). Empty/absent
        // falls through to Knob.SUMMARY default ("detailed"). Do not rewrite concise/auto —
        // the operator may want a thinner public form.
        val summaryRaw = str(out, Knob.SUMMARY)?.trim()?.lowercase().orEmpty()
        if (summaryRaw.isEmpty()) {
            out[Knob.SUMMARY.key] = Knob.SUMMARY.text()
        } else {
            out[Knob.SUMMARY.key] = summaryRaw
        }
        out[Knob.CONTEXT_WINDOW_OVERRIDE.key] = positiveLong(out, Knob.CONTEXT_WINDOW_OVERRIDE)
        out[Knob.USAGE_WARN_PCT.key] = (num(out, Knob.USAGE_WARN_PCT) ?: 0L).coerceIn(0L, 100L)
        out[Knob.USAGE_WARN_TOKENS_5H.key] = clampLong(out, Knob.USAGE_WARN_TOKENS_5H, floor = 0L)
        // anything that is not exactly "off" is "auto" — an unknown value must never silently arm
        // a feature (the r3 invalid-env-value lesson).
        out[Knob.TOOL_SURFACE.key] = offOrAuto(str(out, Knob.TOOL_SURFACE))
        clampPromoted(out)
        return Collections.unmodifiableMap(out)
    }

    // V4-110: shared MCP hosting and request-materialization knobs are positive-only. A floor keeps
    // a typo'd 0/negative from becoming a zero-capacity host or a body cap that rejects every
    // request. The materialization byte budget alone accepts zero for automatic spare-heap sizing.
    private fun clampPromoted(out: MutableMap<String, Any?>) {
        out[Knob.MCP_IDLE_TIMEOUT_MS.key] = clampLong(out, Knob.MCP_IDLE_TIMEOUT_MS, floor = 1_000L)
        out[Knob.MCP_REQUEST_TIMEOUT_MS.key] = clampLong(out, Knob.MCP_REQUEST_TIMEOUT_MS, floor = 1_000L)
        out[Knob.MCP_INITIALIZE_TIMEOUT_MS.key] = clampLong(out, Knob.MCP_INITIALIZE_TIMEOUT_MS, floor = 1_000L)
        out[Knob.MCP_MAX_SERVERS.key] = clampLong(out, Knob.MCP_MAX_SERVERS, floor = 1L)
        out[Knob.MAX_REQUEST_BYTES.key] = clampLong(out, Knob.MAX_REQUEST_BYTES, floor = 1L)
        out[Knob.REQUEST_READ_TIMEOUT_MS.key] = clampLong(out, Knob.REQUEST_READ_TIMEOUT_MS, floor = 1L)
        out[Knob.INGRESS_READ_IDLE_MS.key] = clampLong(out, Knob.INGRESS_READ_IDLE_MS, floor = 1L)
        val heapBytes = clampLong(out, Knob.MATERIALIZATION_HEAP_BYTES, floor = 0L)
        out[Knob.MATERIALIZATION_HEAP_BYTES.key] = if (heapBytes == 0L) {
            0L
        } else {
            maxOf(heapBytes, HeapWeights.request(checkNotNull(num(out, Knob.MAX_REQUEST_BYTES))))
        }
        out[Knob.QUOTA_POLL_INTERVAL_MS.key] = clampLong(
            out,
            Knob.QUOTA_POLL_INTERVAL_MS,
            floor = MIN_QUOTA_POLL_INTERVAL_MS,
        )
    }

    // The one-way kill-switch shape of TOOL_SURFACE.
    private fun offOrAuto(raw: String?): String = if (raw?.trim()?.lowercase() == "off") "off" else "auto"

    // JsonNull IS a JsonPrimitive, so its arm must come first: falling through to el.content turned
    // a JSON null into the four-char string "null" — which a STRING knob then APPLIED, e.g.
    // {"chatgptApiBase": null} in config.json became the literal base URL "null" (DR-48). A JSON
    // null is absence; the knob's default must win.
    fun jsonScalar(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> el.booleanOrNull ?: el.longOrNull ?: el.content
        is JsonObject, is JsonArray -> null
    }

    // One dispatch line per KnobKind. The three arms share nothing — not a value, not a helper, not
    // a failure mode — so they are three functions rather than three inlined blocks (HD-25).

    /** [raw] is the spelling a source gave: its text, or null when the source had no value. Each arm reads only the text. */
    fun coerce(knob: Knob, raw: String?): Any? = when {
        knob == Knob.MIRROR_REASONING -> false
        // The history window governs DELETION, so a word that is neither a day count nor `forever`
        // must be refused by name rather than kept as text no reader understands: a typo that read
        // as a short window would delete history nobody asked it to. Refusing it here puts it in
        // the same reject log, doctor row and PATCH answer as every other bad knob value, and the
        // window stays whatever it already was.
        knob == Knob.HISTORY_RETENTION_DAYS -> coerceString(raw)?.let { HistoryWindowWords.of(it)?.text }
        else -> when (knob.kind) {
            KnobKind.BOOL -> coerceBool(raw)
            KnobKind.NUMBER -> coerceNumber(knob, raw)
            KnobKind.STRING -> coerceText(knob, raw)
        }
    }

    private fun coerceBool(raw: String?): Boolean? = BoolKnobWords.of(raw.toString())

    private fun coerceNumber(knob: Knob, raw: String?): Long? {
        val s = raw.toString().trim().lowercase()
        if (knob in codeModeCounts) return s.toLongOrNull()?.takeIf { it >= 0 }
        // maxInflight AND maxQueued both treat <=0 as unlimited in InflightGate — accept
        // the same named sentinels so PATCH/env maxQueued=unlimited is not rejected.
        return if (knob in unlimitedKnobs && s in unlimitedWords) {
            0L
        } else {
            s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        }
    }

    // raw?. not raw. — Kotlin's null.toString() is the same four-char "null" trap (DR-48).
    private fun coerceString(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    /** A text knob whose reader can use only some words refuses the rest by name (the same reject log, doctor row,
     *  PATCH answer and boot finding as every other bad value). A Projects folder is an ABSOLUTE path, since a relative
     *  one names a different folder per process (Oct 12, §9 audit). A word-valued knob takes only its list
     *  ([KnobWords]), any case: no fallback, so a typo is a finding, never another mode (Oct 13). */
    private fun coerceText(knob: Knob, raw: String?): String? {
        val text = coerceString(raw)
        if (knob == Knob.PROJECTS_FOLDERS) return text?.takeIf { it.split(':').all(ProjectFolderCheck::absolute) }
        val words = KnobWords.of(knob) ?: return text
        return text?.lowercase()?.takeIf { it in words }
    }

    // Shared reads over the merged map — one place each so `normalize` stays a flat clamp table.
    fun str(out: Map<String, Any?>, k: Knob): String? = out[k.key]?.toString()

    fun num(out: Map<String, Any?>, k: Knob): Long? = (out[k.key] as? Long) ?: str(out, k)?.toLongOrNull()

    // Read [k] as a long, substitute [default] when absent, then apply the [floor].
    fun clampLong(
        out: Map<String, Any?>,
        k: Knob,
        floor: Long,
        default: Long = 0L,
        ceiling: Long = Long.MAX_VALUE,
    ): Long = (num(out, k) ?: default).coerceIn(floor, ceiling)

    fun positiveLong(out: Map<String, Any?>, k: Knob): Long? = num(out, k)?.takeIf { it > 0 }
}

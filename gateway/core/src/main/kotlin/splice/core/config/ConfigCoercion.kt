// PORT-OF: server/src/config.mjs coerce/normalize @ pre-public-port-baseline — the knob COERCION
// + NORMALIZATION half of the config engine. Invariants (moved here verbatim from
// ConfigService.kt's header, because this is where they are enforced): normalization floors
// (upstreamTimeout >= 30s, firstByte >= 10s, streamIdle >= 30s or 250ms under CODEX_PROXY_TEST=1,
// authCache >= 5s); showReasoning alias folding; trailing-slash strip on base urls;
// maxInflight >= 0; bool coercion is /^(1|true|yes|on)$/i.
// Split out of ConfigService.kt as a CLASS at HD-M8 (2026-08-16) because that class sat on the
// 14-function ceiling; given its own FILE at HD-25 (2026-08-18) because it was never ConfigService
// state — it holds zero, takes [EnvReader] in its constructor, and the shared file was the only
// thing coupling them.
// The clamp/floor constants travel WITH it and are not optional: `private` at file scope is
// file-private in Kotlin, so leaving them behind in ConfigService.kt would not compile.
package splice.core.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.util.EnvReader

private const val MIN_UPSTREAM_TIMEOUT_MS = 30_000L
private const val MIN_FIRST_BYTE_MS = 10_000L
private const val MIN_STREAM_IDLE_MS = 30_000L
private const val TEST_IDLE_FLOOR_MS = 250L
private const val MIN_AUTH_CACHE_MS = 5_000L
private const val MAX_PORT = 65_535L
private const val MAX_INT = Int.MAX_VALUE.toLong()
private const val MAX_RETRIES = 100L
private const val MAX_FOLD_ROUNDS = 100L
private const val MAX_FOLD_TIER = 100L

/**
 * The knob COERCION + NORMALIZATION half of [ConfigService], split out because the class is at the
 * 14-function ceiling and cannot absorb the dissolved companion (Kotlin style law, 2026-08-16 —
 * HD-M8). [ConfigService] holds one instance and reaches them through it. `internal` because
 * nothing outside :core reads them — [normalizeShowReasoning] was `public` only because a top-level
 * function had nowhere narrower to live, and its sole production caller is [normalize] two lines
 * below it.
 *
 * HD-25 (2026-08-18) moved it to this file and made two changes inside it, both behaviour-neutral:
 * the three constant matchers [coerce] used to rebuild on EVERY call are now properties, and
 * [coerce]'s three per-kind arms are named functions. Everything else is the same names and the
 * same bodies.
 */
internal class ConfigCoercion(private val envReader: EnvReader) {

    // HD-25: these three were constructed INSIDE [coerce], so every call rebuilt a compiled Regex
    // and two sets — and coerce runs once per knob per layer, i.e. hundreds of times per merge, on
    // a path that merges FRESH on every read (this engine's founding invariant). They are constants
    // with no dependency on the arguments, so a property is their right home. Values unchanged.
    private val boolTruth = Regex("^(1|true|yes|on)$", RegexOption.IGNORE_CASE)
    private val unlimitedKnobs = setOf(Knob.MAX_INFLIGHT, Knob.MAX_QUEUED)
    private val unlimitedWords = setOf("", "unlimited", "off", "none")

    // A flat table of floors/clamps — splitting it would scatter the contract (port fidelity).
    fun normalize(raw: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(raw)

        listOf(Knob.CHATGPT_API_BASE, Knob.XAI_API_BASE).forEach { k ->
            out[k.key] = str(out, k)?.trimEnd('/')
        }
        // clampLong's `default` only fires when the value is ABSENT (num returns null); an
        // explicit 0/negative port is PRESENT, so clampLong would still floor-clamp it to 1
        // (unbindable). Ports need pre-commit's `positiveLong ?: default` contract instead —
        // reject non-positive values outright, then clamp only the ceiling.
        out[Knob.PORT.key] =
            (positiveLong(out, Knob.PORT) ?: (Knob.PORT.default as Long)).coerceAtMost(MAX_PORT)
        out[Knob.GROK_PORT.key] =
            (positiveLong(out, Knob.GROK_PORT) ?: (Knob.GROK_PORT.default as Long)).coerceAtMost(MAX_PORT)
        out[Knob.CONTROL_PORT.key] =
            (positiveLong(out, Knob.CONTROL_PORT) ?: (Knob.CONTROL_PORT.default as Long)).coerceAtMost(MAX_PORT)
        out[Knob.MAX_INFLIGHT.key] = clampLong(out, Knob.MAX_INFLIGHT, floor = 0L, ceiling = MAX_INT)
        out[Knob.MAX_QUEUED.key] = clampLong(out, Knob.MAX_QUEUED, floor = 0L, ceiling = MAX_INT)
        // DR-150: this said `default = 2L` — the PRE-G4b number, left behind when Knob's default
        // moved to 4. It is unreachable in production (mergedRaw seeds every knob with its
        // Knob.default before normalize runs, so the key is never absent here), which is exactly
        // why it rotted unnoticed: a reader comparing the two sites learns the wrong number, and
        // any future caller that normalizes an unseeded map silently gets half the retries the
        // knob declares. Read the declared default rather than restating it, so the two cannot
        // diverge again. Dropping the argument is NOT equivalent — it defaults to 0, which the
        // floor of 1 then turns into a single attempt.
        out[Knob.UPSTREAM_RETRIES.key] = clampLong(
            out,
            Knob.UPSTREAM_RETRIES,
            floor = 1L,
            default = Knob.UPSTREAM_RETRIES.default as Long,
            ceiling = MAX_RETRIES,
        )
        out[Knob.FOLD_MAX_CONTINUE.key] =
            clampLong(out, Knob.FOLD_MAX_CONTINUE, floor = 0L, ceiling = MAX_FOLD_ROUNDS)
        out[Knob.FOLD_MAX_TIER.key] = clampLong(out, Knob.FOLD_MAX_TIER, floor = 0L, ceiling = MAX_FOLD_TIER)
        out[Knob.UPSTREAM_TIMEOUT_MS.key] = clampLong(out, Knob.UPSTREAM_TIMEOUT_MS, floor = MIN_UPSTREAM_TIMEOUT_MS)
        out[Knob.FIRST_BYTE_TIMEOUT_MS.key] = clampLong(out, Knob.FIRST_BYTE_TIMEOUT_MS, floor = MIN_FIRST_BYTE_MS)
        val idleFloor = if (envReader("CODEX_PROXY_TEST") == "1") TEST_IDLE_FLOOR_MS else MIN_STREAM_IDLE_MS
        out[Knob.STREAM_IDLE_MS.key] = clampLong(out, Knob.STREAM_IDLE_MS, floor = idleFloor)
        out[Knob.AUTH_CACHE_MS.key] = clampLong(out, Knob.AUTH_CACHE_MS, floor = MIN_AUTH_CACHE_MS)
        out[Knob.SHOW_REASONING.key] = normalizeShowReasoning(str(out, Knob.SHOW_REASONING))
        out[Knob.MIRROR_REASONING.key] = false
        // Summary is operator-controlled (TOML [daemon].summary / env / state). Empty/absent
        // falls through to Knob.SUMMARY default ("detailed"). Do not rewrite concise/auto —
        // the operator may want a thinner public form.
        val summaryRaw = str(out, Knob.SUMMARY)?.trim()?.lowercase().orEmpty()
        if (summaryRaw.isEmpty()) {
            out[Knob.SUMMARY.key] = Knob.SUMMARY.default
        } else {
            out[Knob.SUMMARY.key] = summaryRaw
        }
        out[Knob.CONTEXT_WINDOW_OVERRIDE.key] = positiveLong(out, Knob.CONTEXT_WINDOW_OVERRIDE)
        out[Knob.USAGE_WARN_PCT.key] = (num(out, Knob.USAGE_WARN_PCT) ?: 0L).coerceIn(0L, 100L)
        out[Knob.USAGE_WARN_TOKENS_5H.key] = clampLong(out, Knob.USAGE_WARN_TOKENS_5H, floor = 0L)
        // anything that is not exactly "off" is "auto" — an unknown value must never silently arm
        // a feature (the r3 invalid-env-value lesson).
        out[Knob.TOOL_SURFACE.key] =
            if (str(out, Knob.TOOL_SURFACE)?.trim()?.lowercase() == "off") "off" else "auto"
        return out
    }

    fun normalizeShowReasoning(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        return when {
            v.isEmpty() || v in setOf("0", "false", "off", "none") -> "off"
            v in setOf("text", "mirror", "full", "both", "2") -> "text"
            else -> "thinking"
        }
    }

    // JsonNull IS a JsonPrimitive, so its arm must come first: falling through to el.content turned
    // a JSON null into the four-char string "null" — which a STRING knob then APPLIED, e.g.
    // {"chatgptApiBase": null} in config.json became the literal base URL "null" (DR-48). A JSON
    // null is absence; the knob's default must win.
    fun jsonScalar(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> el.booleanOrNull ?: el.longOrNull ?: el.content
        else -> null
    }

    // One dispatch line per KnobKind. The three arms share nothing — not a value, not a helper, not
    // a failure mode — so they are three functions rather than three inlined blocks (HD-25).
    fun coerce(knob: Knob, raw: Any?): Any? = if (knob == Knob.MIRROR_REASONING) {
        false
    } else {
        when (knob.kind) {
            KnobKind.BOOL -> coerceBool(raw)
            KnobKind.NUMBER -> coerceNumber(knob, raw)
            KnobKind.STRING -> coerceString(raw)
        }
    }

    private fun coerceBool(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        else -> boolTruth.matches(raw.toString().trim())
    }

    private fun coerceNumber(knob: Knob, raw: Any?): Long? {
        val s = raw.toString().trim().lowercase()
        // maxInflight AND maxQueued both treat <=0 as unlimited in InflightGate — accept
        // the same named sentinels so PATCH/env maxQueued=unlimited is not rejected.
        return if (knob in unlimitedKnobs && s in unlimitedWords) {
            0L
        } else {
            s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        }
    }

    // raw?. not raw. — Kotlin's null.toString() is the same four-char "null" trap (DR-48).
    private fun coerceString(raw: Any?): String? = raw?.toString()?.trim()?.ifEmpty { null }

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

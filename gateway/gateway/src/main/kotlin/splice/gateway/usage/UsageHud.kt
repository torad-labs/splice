// PORT-OF: server/src/usage/hud.mjs @ pre-public-port-baseline — invariants: buildUsagePayload stuffs the
// NON-STANDARD fields Claude Code reads from custom gateways (context_window,
// context_window_size, used_percentage) sized from the head's REAL window; cacheLogLine's exact
// line format is watchable via log tail. SEAM (recorded): log lines are injected writers.
// HD-24 (2026-08-17): decomposed — makeOutputClamp moved to OutputClamp.kt/OutputClampPolicy,
// alias parsing to UsageJson.kt, ratelimit/ring persistence to UsageStore.kt and its siblings.
// This residual is stateless again (zero splice.* imports beyond its own package).
package splice.gateway.usage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val FULL_PCT = 100.0

/** JS switches to exponent notation only below 1e-7 (ECMA-262 Number::toString step 5 bound). */
private const val JS_DECIMAL_MIN_EXP = -6

/** The HUD surface: the gateway usage payload and the per-turn cache line. Stateless; collaborators
 *  construct one (`private val hud = UsageHud()`). */
public class UsageHud {
    private val json = UsageJson()

    /** The gateway usage payload with Claude Code's non-standard context fields. */
    public fun buildUsagePayload(usage: TurnUsage, contextWindow: Long?): JsonObject {
        val totalInput = usage.inputTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens
        return buildJsonObject {
            put("input_tokens", usage.inputTokens)
            put(OUTPUT_TOKENS, usage.outputTokens)
            put("cache_creation_input_tokens", usage.cacheCreationInputTokens)
            put("cache_read_input_tokens", usage.cacheReadInputTokens)
            if (contextWindow != null && contextWindow > 0) {
                put("context_window", contextWindow)
                put("context_window_size", contextWindow)
                // JS-number parity: the legacy reference emits this via JSON.stringify and the
                // migration oracle byte-compares. Two notation gaps vs JVM: an integral double prints
                // bare ("0", never "0.0"), and JS stays in decimal notation down to 1e-7 where the
                // JVM flips to E-notation below 1e-3 (0.000367…, never 3.67E-4).
                val pct = totalInput.toDouble() / contextWindow * FULL_PCT
                if (pct == kotlin.math.floor(pct)) {
                    put("used_percentage", pct.toLong())
                } else {
                    put("used_percentage", jsNumber(pct))
                }
            }
        }
    }

    /** JVM Double.toString rendered in JS decimal notation for the E-notation window JS doesn't use
     *  (exponents -1..-6): the digit sequence is shortest-round-trip in both runtimes, only the
     *  notation differs. Exponents <= -7 are E-notation in JS too and cannot arise for a percentage
     *  of a real context window, so any other repr rides through untouched. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun jsNumber(v: Double): JsonElement {
        val s = v.toString()
        val e = s.indexOf('E')
        if (e < 0) return JsonPrimitive(v)
        val exp = s.substring(e + 1).toInt()
        if (exp > 0 || exp < JS_DECIMAL_MIN_EXP) return JsonPrimitive(v)
        val neg = s.startsWith("-")
        val digits = s.substring(if (neg) 1 else 0, e).replace(".", "").trimEnd('0').ifEmpty { "0" }
        return JsonUnquotedLiteral((if (neg) "-" else "") + "0." + "0".repeat(-exp - 1) + digits)
    }

    /** One concise line per completed turn so the cache hit rate is watchable live. Parses via the
     *  SAME [UsageJson.from] the payload uses — a second inline parser here had drifted to the OPPOSITE
     *  cached-token precedence, so the logged hit-rate could disagree with the wire (craft review). */
    public fun cacheLogLine(headTag: String, model: String, usage: JsonObject?, compact: Boolean): String {
        val u = json.from(usage)
        val cached = u.cacheReadInputTokens
        val pct = if (u.inputTokens > 0) (cached.toDouble() / u.inputTokens * FULL_PCT).toInt() else 0
        val compactSuffix = if (compact) " compact" else ""
        return "[$headTag] cache: input=${u.inputTokens} cached=$cached hit=$pct% " +
            "output=${u.outputTokens}$compactSuffix model=$model\n"
    }
}

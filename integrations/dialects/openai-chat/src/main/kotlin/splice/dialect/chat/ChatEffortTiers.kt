// NEW: (HD-24) the reasoning-effort tier picker lifted out of ChatRequestBuilder.kt — a pure
// function of (body.thinking, upstreamModel) with zero coupling to the rest of the file.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.AnthropicRequest

internal class ChatEffortTiers(private val xhighModels: Regex? = null) {

    /**
     * The effort Claude Code's request names, on this vendor's rungs, else the budget tier (Marcos, 2026-10-28:
     * "claude code picks should also be considered, for all heads and all adapters we do. Always."). Claude Code
     * names it in output_config (its /effort, its --effort, which a launch sets to the head's configured effort), and
     * a later message's output_config changes it for the turn, as the Responses adapter reads it. A request that
     * names none keeps the budget tier, so a compaction on the same session's request shape keeps its effort.
     */
    fun chatReasoningEffort(body: AnthropicRequest, upstreamModel: String, raw: JsonObject): String =
        clientEffort(raw, upstreamModel) ?: chatReasoningEffort(body, upstreamModel)

    /** low, medium and high as named; xhigh, max and ultracode as xhigh where the model takes it, else high. */
    private fun clientEffort(raw: JsonObject, upstreamModel: String): String? {
        val fromMessages = (raw["messages"] as? JsonArray)?.asReversed()?.firstNotNullOfOrNull { askedIn(it) }
        return when ((fromMessages ?: askedIn(raw))?.trim()?.lowercase()) {
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            "xhigh", "max", "ultracode" -> if (xhighModels?.containsMatchIn(upstreamModel) == true) "xhigh" else "high"
            else -> null
        }
    }

    /** The effort an object's output_config names, when it is a string. */
    private fun askedIn(element: JsonElement?): String? =
        (((element as? JsonObject)?.get("output_config") as? JsonObject)?.get("effort") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    /**
     * Map Anthropic thinking budget → chat `reasoning_effort`. Default high so backends that
     * support cleartext CoT actually emit `reasoning_content`. Compact turns INHERIT the session
     * effort (AGENTS cache law — a mismatched effort cold-starts the prompt cache); tools are
     * stripped separately, effort is deliberately not compact-aware. Thinking off is not a tier: a turn with no
     * budget gets high, so thinking off never drops the effort (Marcos, 2026-10-27).
     */
    fun chatReasoningEffort(body: AnthropicRequest, upstreamModel: String): String {
        val budget = body.thinking?.budgetTokens
        return when {
            budget == null -> "high"
            // grok-4.6+ (xAI docs 2026-08; older groks clamp xhigh to high). Gated on the model
            // because this dialect also serves arbitrary OpenAI-compatible vendors, and an unknown
            // enum there may be an error rather than a clamp. The regex is supplied by the quirks
            // profile (null = never emit xhigh).
            budget >= XHIGH_BUDGET_FLOOR && xhighModels?.containsMatchIn(upstreamModel) == true -> "xhigh"
            budget >= HIGH_BUDGET_FLOOR -> "high"
            budget >= MEDIUM_BUDGET_FLOOR -> "medium"
            budget > 0L -> "low"
            else -> "high"
        }
    }
}

private const val XHIGH_BUDGET_FLOOR = 64_000L
private const val HIGH_BUDGET_FLOOR = 32_000L
private const val MEDIUM_BUDGET_FLOOR = 8_000L

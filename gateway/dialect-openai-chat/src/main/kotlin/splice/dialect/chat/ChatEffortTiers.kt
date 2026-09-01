// NEW: (HD-24) the reasoning-effort tier picker lifted out of ChatRequestBuilder.kt — a pure
// function of (body.thinking, upstreamModel) with zero coupling to the rest of the file.
package splice.dialect.chat

import splice.core.wire.AnthropicRequest

internal class ChatEffortTiers {

    /**
     * Map Anthropic thinking budget → chat `reasoning_effort`. Default high so backends that
     * support cleartext CoT actually emit `reasoning_content`. Compact turns INHERIT the session
     * effort (AGENTS cache law — a mismatched effort cold-starts the prompt cache); tools are
     * stripped separately, effort is deliberately not compact-aware.
     */
    fun chatReasoningEffort(body: AnthropicRequest, upstreamModel: String): String? {
        if (body.thinking?.disabled == true) return null
        val budget = body.thinking?.budgetTokens
        return when {
            budget == null -> "high"
            // grok-4.6+ (xAI docs 2026-08; older groks clamp xhigh to high). Gated on the model
            // because this dialect also serves arbitrary OpenAI-compatible vendors, and an unknown
            // enum there may be an error rather than a clamp.
            budget >= XHIGH_BUDGET_FLOOR && XHIGH_MODELS.containsMatchIn(upstreamModel) -> "xhigh"
            budget >= HIGH_BUDGET_FLOOR -> "high"
            budget >= MEDIUM_BUDGET_FLOOR -> "medium"
            budget > 0L -> "low"
            else -> "high"
        }
    }
}

// /effort picker budget_tokens -> chat reasoning_effort tier floors
private const val XHIGH_BUDGET_FLOOR = 64_000L
private const val HIGH_BUDGET_FLOOR = 32_000L
private const val MEDIUM_BUDGET_FLOOR = 8_000L

// xhigh is native on grok-4.6 and later (4.6..4.9, 4.10+). grok-5+ lands here when it exists —
// a one-line extension, not a silent cap on a shipped model.
// FILE SCOPE ON PURPOSE: one compiled Regex; as a member it would recompile per instance.
private val XHIGH_MODELS = Regex("grok-4\\.(?:[6-9]|[1-9]\\d)")

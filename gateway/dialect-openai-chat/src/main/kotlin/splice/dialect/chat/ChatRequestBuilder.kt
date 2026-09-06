// NEW: (no Node source — the one genuinely-new translator): Anthropic Messages → OpenAI Chat
// Completions request. This dialect covers Ollama / OpenRouter / LM Studio / DeepSeek and any
// OpenAI-compatible endpoint (the "new vendor = pure TOML, zero Kotlin" goal). Differs from the
// Responses dialect: `messages` (role/content) not `input` items; tool_calls not function_call;
// max_tokens IS honored (unlike the ChatGPT backend); reasoning is a plain field where supported.
package splice.dialect.chat

import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicRequest

public class ChatRequestBuilder(
    private val quirks: ChatQuirks,
    private val showReasoning: ReasoningDisplay = ReasoningDisplay.TEXT,
) {
    private val wire = ChatWireMapper(quirks)
    private val assembler = ChatRequestAssembler(quirks, wire)
    private val effortTiers = ChatEffortTiers()

    public fun build(
        body: AnthropicRequest,
        upstreamModel: String,
        originalModel: String,
        compact: Boolean,
        sessionId: String? = null,
    ): BuiltChatRequest {
        // A compact turn is built EXACTLY like any other turn (2026-09-05, operator law): same
        // system, same tools, same tool_choice, same effort. Every compact-only reshaping this
        // builder used to do (directive appended to the system message, tools stripped) moved the
        // backend's prompt-cache prefix from token zero, so every compaction read the whole
        // transcript cold. `compact` reaches TurnMeta for the response side only.
        val messages = wire.messagesArray(body.system, body)
        val emitTools = quirks.supportsTools && body.tools.isNotEmpty()
        val effort = effortTiers.chatReasoningEffort(body, upstreamModel)
        // TIER-1 (#924): the request is a CLOSED ChatRequest DTO (see chatRequestObject) — a knob
        // that doesn't belong can't be added without a field.
        val cacheKey = quirks.sessionCacheKeyPrefix?.let { prefix -> sessionId?.let { "$prefix:$it" } }
        val req = assembler.chatRequestObject(upstreamModel, messages, emitTools, body, ChatKnobs(effort, cacheKey))
        val meta = TurnMeta(
            compact = compact,
            showReasoning = showReasoning,
            stream = body.stream,
            originalModel = originalModel,
            upstreamModel = upstreamModel,
            clientMaxTokens = body.maxTokens?.takeIf { it > 0 },
            effort = effort ?: "n/a",
            summary = if (effort != null) "detailed" else null,
            budgetTokens = body.thinking?.budgetTokens,
        )
        return BuiltChatRequest(req, meta)
    }
}

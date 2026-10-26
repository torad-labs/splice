// NEW: failure-handling slice 0, the harness. The Provider each scripted head serves through, assembled from its
// dialect. A :features-turns test may reach the dialects and the Provider port, never the provider layer above them
// (ModuleLawsTest, HD-11), so these stand where OpenAiChatProvider and OpenAiResponsesProvider would, as
// TestResponsesProvider does for Responses (V4-24).
package splice.head.scripted

import splice.core.parse.AnthropicTurnBody
import splice.core.prompt.SystemPromptMode
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.LogSink
import splice.dialect.chat.ChatCompactionTail
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder
import splice.dialect.chat.ChatResendOnce
import splice.dialect.chat.ChatStreamTranslator
import splice.dialect.chat.ChatSystemPrompt
import splice.dialect.chat.ChatTurnContext
import splice.dialect.chat.ID_SLOT_FIELD
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderIdentity
import splice.upstream.ProviderTuning
import splice.upstream.ReanchorPolicy
import splice.upstream.StreamTranslator
import splice.upstream.TurnEnd
import splice.upstream.TurnSignals

/**
 * A Chat Completions head: the assembly OpenAiChatProvider makes from the chat dialect (its request builder,
 * compaction tail, system prompt seam, resend-once policy and stream translator), with reasoning shown as text. A
 * change to that assembly is made here too, or the harness stops standing for the head it drives.
 */
internal class ScriptedChatProvider(
    tuning: ProviderTuning,
    quirks: ChatQuirks,
) : Provider, ProviderIdentity by tuning {
    override val upstreamUrl: String = "${tuning.locations.baseUrl}/chat/completions"
    override val replayReasoning: Boolean = false
    override val showReasoning: ReasoningDisplay = ReasoningDisplay.TEXT

    private val builder = ChatRequestBuilder(quirks)
    private val compactionTail = ChatCompactionTail()
    private val systemPrompt = ChatSystemPrompt()

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val built = builder.build(body.typed, catalog.stripSuffixes(body.typed.model), compact, sessionId, body.raw)
        return BuiltTurn(
            built.req,
            built.meta,
            onEnd = built.lease?.let { lease -> TurnEnd(lease::end) },
            routingFields = setOf(ID_SLOT_FIELD),
        )
    }

    override fun withCompactionTail(turn: BuiltTurn, instructions: String): BuiltTurn =
        turn.copy(requestBody = compactionTail.append(turn.requestBody, instructions))

    override fun withSystemPrompt(turn: BuiltTurn, prompt: String, mode: SystemPromptMode): BuiltTurn =
        turn.copy(requestBody = systemPrompt.apply(turn.requestBody, prompt, mode))

    override fun reanchorPolicy(meta: TurnMeta): ReanchorPolicy = ChatResendOnce

    override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        ChatStreamTranslator(
            ChatTurnContext(
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                idleCapMs = watchdog.streamIdle.inWholeMilliseconds,
                totalCapMs = watchdog.totalCap.inWholeMilliseconds,
            ),
        )
}

/**
 * A Responses head on the dialect's own quirk profile, which is the OpenAI platform's profile under another tag. Its
 * rounds ride the websocket when [webSocket] is on.
 */
internal class ScriptedResponsesProvider(
    tuning: ProviderTuning,
    private val webSocket: Boolean,
    log: LogSink,
) : ResponsesProvider(
    tuning,
    ReasoningSettings(ReasoningDisplay.TEXT, replay = false, effort = "high", summary = "detailed"),
    ResponsesQuirks(providerTag = tuning.key, lite = ResponsesLiteQuirks()).withWebSocketToml(webSocket),
    log = log,
) {
    override val supportsWebSocket: Boolean get() = webSocket
}

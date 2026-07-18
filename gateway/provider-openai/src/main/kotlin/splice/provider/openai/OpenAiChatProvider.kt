// NEW: the openai-chat provider — the "any OpenAI-compatible vendor, zero Kotlin" payoff.
// Ollama / OpenRouter / LM Studio / DeepSeek all speak Chat Completions; a new vendor is a TOML
// table (base_url + api-key + models) instantiating THIS provider with the chat dialect. api-key
// auth (env or file). The stream machine is the chat translator (different SSE shape from
// Responses). This proves the dialect axis: one new dialect unlocks a whole family of backends.
package splice.provider.openai

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder
import splice.dialect.chat.ChatStreamTranslator
import splice.dialect.chat.ChatTurnContext
import splice.spi.BuiltTurn
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.StreamTranslator
import splice.spi.TurnSignals

public class OpenAiChatProvider(
    private val tuning: ProviderTuning,
    private val quirks: ChatQuirks,
) : Provider, ProviderIdentity by tuning {

    override val upstreamUrl: String = "${tuning.baseUrl}/chat/completions"
    override val showReasoning: String = "text"
    override val replayReasoning: Boolean = false // chat dialect has no encrypted-reasoning replay

    private val builder = ChatRequestBuilder(quirks)

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val upstreamModel = catalog.stripSuffixes(body.typed.model)
        val built = builder.build(body.typed, upstreamModel, body.typed.model, compact)
        return BuiltTurn(built.req, built.meta)
    }

    override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        ChatStreamTranslator(
            ChatTurnContext(
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                idleCapMs = watchdog.streamIdle.inWholeMilliseconds,
                totalCapMs = watchdog.totalCap.inWholeMilliseconds,
            ),
        )

    override fun extraHeaders(creds: Credentials): Map<String, String> = mapOf("Accept" to "text/event-stream")
}

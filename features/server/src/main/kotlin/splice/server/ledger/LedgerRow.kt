// NEW: server mode (docs/specs/server-mode.md, F5, ledger-rows). One line of the server's usage ledger: one request a
// person sent the server, whether it was served, refused or broken off. It says who sent it (the person, the team they
// were in then, the device by its key's prefix), what they asked (the command, the model, the route), where it went
// (the upstream that took it), how it ended (the status the row stands for and its outcome), what it used (tokens as
// the provider billed them) and cost (at the model's rate card), and how fast it went. Never a word of the prompt or
// of the answer.
package splice.server.ledger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class LedgerRow(
    /** When the request arrived, ISO-8601 UTC. */
    val at: String,
    val person: String,
    /** The person's team when the request ran, or null when they had none. */
    val team: String? = null,
    /** The prefix of the key the request came with. */
    val device: String,
    val command: String,
    /** The model that answered, or the one asked for; null for a request refused before its body was read. */
    val model: String? = null,
    /** [ROUTE_MESSAGES] or [ROUTE_CHAT]. */
    val route: String,
    /** The upstream of the provider's pool that took it, or null for a provider that is one server. */
    val upstream: String? = null,
    val status: Int,
    /** One of the outcomes in [RowOutcome]. */
    val outcome: String,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("cached_tokens") val cachedTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("ttft_ms") val ttftMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("prefill_tps") val prefillTps: Double? = null,
    @SerialName("decode_tps") val decodeTps: Double? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
)

/** The route of a turn Claude Code sends, Anthropic's Messages API. */
internal const val ROUTE_MESSAGES: String = "messages"

/** The route of a turn an OpenAI-style client sends, chat completions. */
internal const val ROUTE_CHAT: String = "chat_completions"

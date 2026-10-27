// NEW: failure-handling slice 3 (2026-10-26). Every head's reading of its own failures, composed in one place: its
// API type's standard reader, and the deviation its own vendor declared in its own module. The assembly picks one line
// per head (separation: a shared file carries at most one dispatch line per head), so a vendor's facts stay in the
// vendor's module and the reading a head uses is readable here in full.
package splice.app.provider

import splice.dialect.anthropic.failure.AnthropicReplies
import splice.dialect.chat.failure.ChatReplies
import splice.dialect.responses.failure.ResponsesReplies
import splice.provider.codex.failure.CodexReplies
import splice.provider.grok.GrokReplies
import splice.provider.kimi.KimiReplies
import splice.provider.muse.MuseReplies
import splice.provider.openai.local.LocalRuntimeReplies
import splice.upstream.failure.Replies

/** The reading each head serves with. A head whose vendor declared nothing reads its API type's standard alone. */
internal object HeadReplies {
    /** A generic Anthropic Messages vendor: the standard alone. */
    val anthropic: Replies = Replies(AnthropicReplies)

    /** Kimi: its plan-tier answer is a plan rather than a dead login, and its 5-hour limit names that window. */
    val kimi: Replies = Replies(AnthropicReplies, KimiReplies)

    /** A hosted Chat Completions vendor: the standard alone. */
    val chat: Replies = Replies(ChatReplies)

    /** xAI on the Chat dialect: its expired token is a dead credential, spelled 403. */
    val grokChat: Replies = Replies(ChatReplies, GrokReplies)

    /** A local runtime: a model still loading, a context refusal that names its window, a model it does not hold. */
    val localChat: Replies = Replies(ChatReplies, LocalRuntimeReplies)

    /** A hosted Responses vendor: the standard alone. */
    val responses: Replies = Replies(ResponsesReplies)

    /** xAI on the Responses dialect, with the same dead-token answer. */
    val grokResponses: Replies = Replies(ResponsesReplies, GrokReplies)

    /** codex: its spent plan window, its policy refusals and its websocket limit. */
    val codex: Replies = Replies(ResponsesReplies, CodexReplies)

    /** Muse: a soft reset on a 429, a plan limit in Muse's own words, and its key mint's own refusals. */
    val muse: Replies = Replies(ResponsesReplies, MuseReplies)
}

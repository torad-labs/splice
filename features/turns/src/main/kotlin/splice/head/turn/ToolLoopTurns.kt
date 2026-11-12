// Fix 4 of the Oct 27 Responses fixes: Claude Code sends one request for the person's prompt and one more for each
// tool result after it, each a new POST carrying the whole conversation, and together they are one turn. codex-rs keeps
// the turn state its backend returns for the whole turn (core/src/client.rs: ModelClientSession's turn_state, set once
// per turn) and starts the next prompt without one. This tells which earlier request a POST continues, so its
// TurnUpstreamHeaders joins that turn's.
//
// A conversation has one live turn: the one its latest prompt opened. A request continues it when it names the same
// conversation and carries the same prompt at the same place; a new prompt replaces it, and starts without a turn
// state. The conversation is the session, the dialect's conversation key and the subagent the request comes from: two
// subagents of one session whose first messages are identical share a conversation key by design
// (TurnScope.conversationKey), and Claude Code names each on its own requests (x-claude-code-agent-id, on every
// request of all 13 subagents in claudex's trace from Oct 27 05:44 to 08:26 CT and on no main thread's).
//
// The prompt is the person's words, wherever Claude Code puts them: a user message that answers no tool call, and also
// one that answers a tool call and carries words beside it, which is how the client sends text a person types while a
// tool runs (the same trace, 08:08 to 08:21 CT: 29 distinct user messages carried a tool result and then a text
// block). The notes the client writes for the model — system reminders, a background task's notification, the caveat
// before a command — ride as text blocks too and are never a message (ClientEcho.kt in integrations/claude-code), so
// a result with one beside it continues its turn.
//
// A request with no session, no conversation key or no prompt joins nothing (a bare curl, a head whose dialect keys no
// conversation). Nothing is dropped while it can still be continued, so the live turns grow with the conversations a
// head has seen since it started: two digests and a reference each, about half a kilobyte, against the 52
// conversations of that trace's 2 hours 42 minutes of heavy use.
package splice.head.turn

import kotlinx.serialization.json.JsonArray
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.core.turn.TurnUpstreamHeaders
import splice.core.util.JsonWire
import splice.core.wire.AnthropicMessage
import splice.core.wire.ContentBlock
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** What the client opens its own notes with, as ClientEcho reads them: text beginning with one of these is Claude
 *  Code writing to the model, not a person writing a prompt. */
private val CLIENT_NOTES = listOf("<system-reminder>", "<local-command-caveat>", "<task-notification>")

internal class ToolLoopTurns {
    /** Each conversation's live turn, by the digest of who owns it. */
    private val live = HashMap<String, LiveTurn>()

    /** [meta]'s request joins the Claude Code turn it continues, or opens one. Called before the request is sent.
     *  [agent] is the subagent Claude Code heads the request with, null for a session's main thread. */
    fun join(meta: TurnMeta, body: AnthropicTurnBody, agent: String?) {
        val owner = ownerOf(meta, agent) ?: return
        val prompt = promptOf(body) ?: return
        synchronized(live) {
            live[owner]?.takeIf { it.prompt == prompt }?.let { meta.upstreamHeaders.continues(it.headers) }
            live[owner] = LiveTurn(prompt, meta.upstreamHeaders)
        }
    }

    /** One conversation's live turn: the prompt that opened it, and the holder its requests share. */
    private data class LiveTurn(val prompt: String, val headers: TurnUpstreamHeaders)

    /** A digest of the session, the conversation and the subagent; null when the request names no conversation. */
    private fun ownerOf(meta: TurnMeta, agent: String?): String? {
        val ids = listOf(meta.scope.sessionId, meta.scope.conversationKey)
        if (ids.any { it.isNullOrEmpty() }) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((ids + agent.orEmpty()).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest.digest())
    }

    /** A digest of the person's prompt and where it sits: kept in memory only, and never the words. */
    private fun promptOf(body: AnthropicTurnBody): String? {
        val at = body.typed.messages.indexOfLast { it.role == "user" && opensATurn(it) }
        val prompt = (body.raw["messages"] as? JsonArray)?.getOrNull(at) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$at\u0000".toByteArray(Charsets.UTF_8))
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { JsonWire.write(prompt, it) }
        return HexFormat.of().formatHex(digest.digest())
    }

    /** Whether this user message is a prompt: it answers no tool call, or it carries the person's words beside one. */
    private fun opensATurn(message: AnthropicMessage): Boolean =
        message.content.none { it is ContentBlock.ToolResultBlock } || message.content.any { block -> words(block) }

    /** A text block the person wrote: blank text and the client's own notes are not. */
    private fun words(block: ContentBlock): Boolean {
        val text = (block as? ContentBlock.TextBlock)?.text?.trimStart() ?: return false
        return text.isNotEmpty() && CLIENT_NOTES.none { text.startsWith(it) }
    }
}

// Fix 4 of the Oct 27 Responses fixes: Claude Code sends one request for the person's prompt and one more for each
// tool result after it, each a new POST carrying the whole conversation, and together they are one turn. codex-rs keeps
// the turn state its backend returns for the whole turn (core/src/client.rs: ModelClientSession's turn_state, set once
// per turn) and starts the next prompt without one. This tells which earlier request a POST continues, so its
// TurnUpstreamHeaders joins that turn's.
//
// A request continues a turn when it names the turn's session and conversation, and its person's prompt (the last user
// message that answers no tool call) is the turn's prompt, the same message at the same place: a tool result appends
// after the prompt, and a new prompt is a new last such message. A request with no session, no conversation key or no
// prompt joins nothing (a bare curl, a head whose dialect keys no conversation).
package splice.head.turn

import kotlinx.serialization.json.JsonArray
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.core.turn.TurnUpstreamHeaders
import splice.core.util.JsonWire
import splice.core.wire.ContentBlock
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** The turns a later request can still continue, across every session of the head: past this, the turn left alone
 *  longest is forgotten, and a request that would have continued it starts without a turn state, as a new prompt
 *  does. */
private const val OPEN_TURNS = 1_024

internal class ToolLoopTurns(private val bound: Int = OPEN_TURNS) {
    private val open = LinkedHashMap<String, TurnUpstreamHeaders>()

    /** [meta]'s request joins the Claude Code turn it continues, or opens one. Called before the request is sent. */
    fun join(meta: TurnMeta, body: AnthropicTurnBody) {
        val key = turnOf(meta, body) ?: return
        synchronized(open) {
            open.remove(key)?.let(meta.upstreamHeaders::continues)
            open[key] = meta.upstreamHeaders
            while (open.size > bound) open.remove(open.keys.first())
        }
    }

    /** A digest of the session, the conversation and the prompt: kept in memory only, and never the words. */
    private fun turnOf(meta: TurnMeta, body: AnthropicTurnBody): String? {
        val owner = listOf(meta.scope.sessionId, meta.scope.conversationKey)
            .takeIf { ids -> ids.none { it.isNullOrEmpty() } }
        val at = body.typed.messages.indexOfLast { message ->
            message.role == "user" && message.content.none { it is ContentBlock.ToolResultBlock }
        }
        val prompt = (body.raw["messages"] as? JsonArray)?.getOrNull(at)
        if (owner == null || prompt == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${owner.joinToString("\u0000")}\u0000$at\u0000".toByteArray(Charsets.UTF_8))
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { JsonWire.write(prompt, it) }
        return HexFormat.of().formatHex(digest.digest())
    }
}

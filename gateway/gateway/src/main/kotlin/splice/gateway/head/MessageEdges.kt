// NEW: V4-130, FEATURES.md 6 — message edges observed on the wire. Claude Code's SendMessage is a
// tool the MODEL calls, so every edge a session sends passes through this proxy twice: once as the
// tool_use block of the assistant turn that made the call (in the response), and again as that same
// block inside the history of every later request. This reads the request side, where the block is
// already parsed (ActivityLabel reads the same last assistant message), so no response stream is
// touched and a turn that fails mid-stream still reports the calls it completed.
//
// ONLY THE LAST ASSISTANT MESSAGE IS READ. The history grows by one assistant message per round, so
// the last one is the only one this request can be the first to carry; reading the whole history
// would re-find every old call on every turn. A call is reported once per tool_use id: the same
// message reappears in retries and in the 30-second activity fork, which carries the transcript and
// the session header. The id set is an in-memory LRU; after a restart the store's own id column
// (MessageEdgeStore) makes a re-report harmless.
//
// NO TEXT, ever: `to` and the tool_use id are all that leave this class. The `message` field stays in
// the transcript.
package splice.gateway.head

import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock.ToolUseBlock

/** How many tool_use ids are remembered. A call is re-sent only while it is the last assistant
 *  message of its own session, so the window needs to cover the sessions one head serves at once. */
private const val REMEMBERED_CALLS = 4096
private const val SEND_MESSAGE = "SendMessage"
private const val ROLE_ASSISTANT = "assistant"

internal class MessageEdges(private val events: HeadEvents) {
    private val seen = LinkedHashSet<String>()

    /** Reports every SendMessage call the request's last assistant message made that this head has
     *  not reported yet. A request without a session header reports nothing: an edge needs a sender. */
    fun observe(sessionId: String?, request: AnthropicRequest) {
        val session = sessionId ?: return
        val calls = request.messages.lastOrNull { it.role == ROLE_ASSISTANT }?.content.orEmpty()
            .filterIsInstance<ToolUseBlock>()
            .filter { it.name == SEND_MESSAGE && it.id.isNotEmpty() }
        for (call in calls) {
            val to = (call.input["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            if (to != null && firstSight(call.id)) events.messageSent(session, to, call.id)
        }
    }

    private fun firstSight(id: String): Boolean = synchronized(seen) {
        val added = seen.add(id)
        if (seen.size > REMEMBERED_CALLS) seen.remove(seen.first())
        added
    }
}

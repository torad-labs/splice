// NEW: the harness's port (docs/specs/harness.md, decisions 2 and 11). What a request says about itself that the
// harness reads, taken once as the request is prepared, where the parsed body and the client's headers are both at hand.
package splice.head.port

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.util.JsonScalars

/** What the harness reads from one request. */
public data class HarnessRequest(
    val sessionId: String?,
    /** Claude Code's agent header: null on the main thread, the subagent's id on a subagent's. */
    val agentId: String?,
    /** The client name the user agent names in its parentheses: `cli` is interactive, `sdk-cli` is print mode. */
    val client: String?,
    val shape: HarnessShape,
    /** The model the request names, after splice resolved it. */
    val model: String,
    /** The content of the client's first user message, as the client sent it; null when it sent none. */
    val firstUser: JsonElement?,
) {
    /** Whether the history holds an assistant message. */
    public val answered: Boolean get() = shape.answered

    /** The thread the request belongs to: the session's main thread, or one subagent of it by its agent id. */
    public val thread: String? get() = sessionId?.let { session -> agentId?.let { "$session/$it" } ?: session }

    /** A request that can be a thread's turn: one that streams and carries tools. Side requests carry none. */
    public val turn: Boolean get() = shape.streams && shape.tools

    /** The text of the client's first user message, its text blocks joined, as the client sent them. */
    public fun firstUserText(): String {
        val content = firstUser
        if (content !is JsonArray) return content?.let { JsonScalars.str(it) }.orEmpty()
        return content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { JsonScalars.str(it, TYPE) == TEXT }?.let { JsonScalars.str(it, TEXT) }
        }.joinToString("\n")
    }
}

/** What a request is, for splice's own conditions on every capability. */
public data class HarnessShape(
    val streams: Boolean,
    val tools: Boolean,
    /** Whether the history holds an assistant message. A first turn holds none, which rules out a compaction and the
     *  turn after it. */
    val answered: Boolean,
    /** Claude Code's auto-mode review, for which no capability is ever called. */
    val review: Boolean = false,
    /** The turn holds a model-server slot of its own (a llama-server slot lease), which would sit idle while it
     *  waits. */
    val slotHeld: Boolean = false,
    /** The session a Context Getter's request works for, as its `x-splice-gathers-for` header names it (decision 9):
     *  such a request never calls a capability. Null for every request no gatherer sent. */
    val workedFor: String? = null,
    /** The door the turn came through (docs/specs/door-default.md), as the listener that relayed it named it: a
     *  Context Getter for its session is started on the door too (decision 9). Null for a turn that came straight to
     *  its head. */
    val door: String? = null,
)

/** Reads a [HarnessRequest] off a prepared request. */
internal class HarnessRequests {
    /** [session] is the session the head resolved (the body's, else the header's), [userAgent] the client's header,
     *  [model] the model splice resolved, [shape] what the preparation already knows the request is. */
    fun of(
        parsed: AnthropicTurnBody,
        session: String?,
        userAgent: String?,
        model: String,
        shape: HarnessShape,
    ): HarnessRequest {
        val messages = parsed.raw["messages"] as? JsonArray
        val first = messages?.firstOrNull { JsonScalars.str(it as? JsonObject, "role") == "user" } as? JsonObject
        return HarnessRequest(
            sessionId = session,
            agentId = parsed.agentId,
            client = clientName(userAgent),
            shape = shape,
            model = model,
            firstUser = first?.get("content"),
        )
    }

    /** `claude-cli/2.1.326 (external, sdk-cli)` names `sdk-cli`: the last item inside the parentheses. */
    private fun clientName(userAgent: String?): String? {
        val inside = userAgent?.substringAfter('(', "")?.substringBefore(')')?.takeIf(String::isNotBlank)
        return inside?.substringAfterLast(',')?.trim()
    }
}

private const val TYPE = "type"
private const val TEXT = "text"

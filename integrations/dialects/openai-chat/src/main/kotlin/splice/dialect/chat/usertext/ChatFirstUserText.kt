// NEW: the harness on the openai-chat wire (docs/specs/harness.md, decision 5). A capability's text goes at the end of
// the conversation's first user message, after what the person typed, the way ChatUserContent extends a user message
// for the compaction tail too. No user message is added, so the request never holds two user messages in a row that
// the client did not send.
package splice.dialect.chat.usertext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.chat.CONTENT

/** Places a text at the end of the first user message. A request with no user message, or one whose content is
 *  neither text nor parts, is left the same instance, which says the text was not placed. */
internal class ChatFirstUserText {
    private val userContent = ChatUserContent()

    public fun append(request: JsonObject, text: String): JsonObject {
        val messages = request[MESSAGES] as? JsonArray
        val at = messages?.indexOfFirst { JsonScalars.str(it as? JsonObject, ROLE) == USER } ?: -1
        val message = messages?.getOrNull(at) as? JsonObject ?: return request
        val content = userContent.extended(message[CONTENT], text) ?: return request
        val next = messages.toMutableList().also { it[at] = JsonObject(message + (CONTENT to content)) }
        return JsonObject(request + (MESSAGES to JsonArray(next)))
    }
}

private const val MESSAGES = "messages"
private const val ROLE = "role"
private const val USER = "user"

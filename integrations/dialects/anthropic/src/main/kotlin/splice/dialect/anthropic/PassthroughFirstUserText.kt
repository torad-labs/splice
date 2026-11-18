// NEW: the harness on the anthropic passthrough wire (docs/specs/harness.md, decision 5). A capability's text goes
// into the request's first user message as a text block of its own, after the client's blocks, the way
// PassthroughUserContent extends a user message for the compaction tail too.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Places a text block at the end of the first user message. A request with no user message, or one whose content
 *  is neither text nor blocks, is left the same instance, which says the text was not placed. */
public class PassthroughFirstUserText {
    private val userContent = PassthroughUserContent()

    public fun append(request: JsonObject, text: String): JsonObject {
        val messages = request[MESSAGES] as? JsonArray
        val at = messages?.indexOfFirst { JsonScalars.str(it as? JsonObject, ROLE) == USER } ?: -1
        val message = messages?.getOrNull(at) as? JsonObject ?: return request
        val content = userContent.extended(message[CONTENT], text) ?: return request
        val next = messages.toMutableList().also { it[at] = JsonObject(message + (CONTENT to content)) }
        return JsonObject(request + (MESSAGES to JsonArray(next)))
    }
}

private const val ROLE = "role"
private const val USER = "user"

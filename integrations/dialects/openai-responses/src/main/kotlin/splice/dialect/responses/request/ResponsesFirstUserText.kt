// NEW: the harness on the responses wire (docs/specs/harness.md, decision 5). The input carries each of the client's
// text blocks as an item of its own, so a capability's text is one more user item after the conversation's opening
// user items, before the first answer. Every item the client's request became keeps its bytes.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Places a text as a user item after the opening user items. A request with no user item is left the same
 *  instance, which says the text was not placed. */
internal class ResponsesFirstUserText {
    private val userItem = ResponsesUserItem()

    fun append(request: JsonObject, text: String): JsonObject {
        val input = request[INPUT] as? JsonArray ?: return request
        val from = input.indexOfFirst(::user)
        if (from < 0) return request
        var to = from
        while (to < input.size && user(input[to])) to++
        val next = input.toMutableList().also { it.add(to, userItem.of(text)) }
        return JsonObject(request + (INPUT to JsonArray(next)))
    }

    private fun user(item: JsonElement): Boolean =
        JsonScalars.str(item as? JsonObject, ROLE) == USER
}

private const val INPUT = "input"
private const val ROLE = "role"
private const val USER = "user"

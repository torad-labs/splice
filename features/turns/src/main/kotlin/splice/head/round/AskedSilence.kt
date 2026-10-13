// NEW: Oct 13, 2026 — whether a request asked the model for nothing, so an empty answer to it is never carried on.
package splice.head.round

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Claude Code's prompt-suggestion request ends with a user message that opens with this, and asks for silence
 *  when there is nothing worth suggesting (Claude Code 2.1.296, the SUGGESTION MODE prompt). */
private const val SUGGESTION_MODE = "[SUGGESTION MODE:"

/** A request whose last message asks the model to stay silent when it has nothing to add. A round it ends with
 *  reasoning alone is that silence, so EmptyAnswerRetry never asks the model for an answer to it. Read from the
 *  upstream body's last user item (Responses `input`, Anthropic and chat `messages`): the prompt rides there as the
 *  client wrote it, in every dialect, and only the last one is the request; an earlier one is history, and an item
 *  splice adds after it (a developer or system layer) carries no user role. */
internal class AskedSilence {
    fun of(requestBody: JsonObject): Boolean {
        val items = (requestBody["input"] ?: requestBody["messages"]) as? JsonArray ?: return false
        val last = items.lastOrNull { role(it) == "user" } ?: return false
        return texts(last).any { it.trimStart().startsWith(SUGGESTION_MODE) }
    }

    private fun role(item: JsonElement): String? = ((item as? JsonObject)?.get("role") as? JsonPrimitive)?.content

    private fun texts(element: JsonElement): Sequence<String> = when (element) {
        is JsonPrimitive -> if (element.isString) sequenceOf(element.content) else emptySequence()
        is JsonArray -> element.asSequence().flatMap { texts(it) }
        is JsonObject -> sequenceOf("text", "content").mapNotNull { element[it] }.flatMap { texts(it) }
    }
}

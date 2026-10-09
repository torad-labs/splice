// NEW: V4-444 — what a tool call was for, in words, so a session card never shows a command or a path.
package splice.sessions.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import java.net.URI

/** A call's input JSON read as one line a person can read: the description the model wrote, else the name of what it
 *  touched. The command is never one of them, and a path is cut to its file name. Empty when the call says nothing
 *  worth reading or its input is not an object. */
internal object CallSummary {
    private val pathKeys = listOf("file_path", "notebook_path", "path")
    private val targetKeys = listOf("pattern", "query")

    fun of(tool: String, input: String): String {
        // An input that is not JSON has nothing a card may say; empty is the answer, not a lost failure.
        val body = JsonScalars.objectOrNull(Json, input) ?: return ""
        if (tool == AskedQuestions.TOOL) return question(body) ?: ""
        return text(body["description"]) ?: pathName(body) ?: target(body) ?: host(body) ?: ""
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun question(body: JsonObject): String? {
        val first = (body["questions"] as? JsonArray)?.firstOrNull() as? JsonObject
        return text(first?.get("question"))
    }

    private fun pathName(body: JsonObject): String? {
        val path = pathKeys.firstNotNullOfOrNull { text(body[it]) } ?: return null
        return path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    private fun target(body: JsonObject): String? = targetKeys.firstNotNullOfOrNull { text(body[it]) }

    private fun host(body: JsonObject): String? {
        val url = text(body["url"]) ?: return null
        // A malformed url has no host to name; the card says nothing for it.
        return try {
            URI(url).host
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }
}

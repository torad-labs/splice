// NEW: V4-444 — what a session waiting on its operator asked: each AskUserQuestion question whole, with its option labels
// and whether several may be chosen, so a session's card shows the question and its choices. The descriptions under each
// option stay in the terminal where the question is answered; the card only has to say what is being asked.
package splice.sessions.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonScalars

// why: AskUserQuestion's own schema allows one to four questions; past that it is not a question Claude Code shows, so
// the card does not show it either.
private const val MAX_QUESTIONS = 4

// why: the same schema allows two to four options under each question.
private const val MAX_OPTIONS = 4

// why: a question is shown whole on the card, but one card is not a transcript page.
private const val QUESTION_CHARS = 500

// why: an option is a short label (the schema asks for one to five words); its description stays in the terminal.
private const val LABEL_CHARS = 80

internal object AskedQuestions {
    const val TOOL: String = "AskUserQuestion"

    /** The questions [tool]'s [input] asks, or null when it is not an ask-the-user call or asks nothing readable. */
    fun of(tool: String?, input: String): JsonArray? {
        if (tool != TOOL) return null
        // An input that is not JSON asks nothing a card can show; the card falls back to the call's own line.
        val body = JsonScalars.objectOrNull(Json, input)
        val questions = (body?.get("questions") as? JsonArray).orEmpty()
            .take(MAX_QUESTIONS)
            .mapNotNull { it as? JsonObject }
        val asks = buildJsonArray {
            for (question in questions) {
                val text = text(question["question"]) ?: continue
                addJsonObject {
                    put("question", text.take(QUESTION_CHARS))
                    putJsonArray("options") {
                        labels(question["options"]).forEach { add(it) }
                    }
                    put("multi", (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
            }
        }
        return asks.takeIf { it.isNotEmpty() }
    }

    private fun labels(options: JsonElement?): List<String> = (options as? JsonArray).orEmpty().take(MAX_OPTIONS)
        .mapNotNull { option -> text((option as? JsonObject)?.get("label"))?.take(LABEL_CHARS) }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}

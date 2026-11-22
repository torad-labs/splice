// NEW: agy-desktop-parity-2 (docs/specs/desktop-antigravity.md, decision 5). Reads a session's Claude transcript
// when a live session switches to Gemini and formats the lossy handoff summary (user messages, assistant text, and
// tool call names/inputs — never thinking, tool results, sidechain, or meta lines) for the first prompt to agy.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Builds a clean conversation handoff from Claude Code's JSONL transcript for a mid-session switch to Gemini. */
internal class LiveHandoff {
    /** Reads [transcriptFile] and formats turns agy has not seen yet; null when there is nothing to carry over. */
    fun of(transcriptFile: Path, agyResumed: Boolean): String? {
        val rawLines = readLinesOrEmpty(transcriptFile)
        val parsed = rawLines.mapNotNull { JsonScalars.objectOrNull(Json, it) }
            .filter { !isSkipped(it) }
        val relevant = if (agyResumed) afterLastGeminiTurn(parsed) else parsed
        val entries = relevant.mapNotNull(::formatLine)
        return entries.takeIf { it.isNotEmpty() }?.let { HANDOFF_HEADER + it.joinToString("\n\n") }
    }

    private fun readLinesOrEmpty(path: Path): List<String> =
        if (!Files.isRegularFile(path)) {
            emptyList()
        } else {
            try {
                Files.readAllLines(path)
            } catch (_: IOException) {
                emptyList()
            }
        }

    private fun isSkipped(line: JsonObject): Boolean =
        JsonScalars.bool(line, "isSidechain") == true || JsonScalars.bool(line, "isMeta") == true

    private fun afterLastGeminiTurn(lines: List<JsonObject>): List<JsonObject> {
        val lastGemini = lines.indexOfLast { line ->
            JsonScalars.str(line, "type") == "assistant" &&
                JsonScalars.str(line["message"] as? JsonObject, "model").orEmpty().startsWith(ANTIGRAVITY_MODEL_PREFIX)
        }
        return if (lastGemini < 0) lines else lines.drop(lastGemini + 1)
    }

    private fun formatLine(line: JsonObject): String? {
        val message = line["message"] as? JsonObject ?: return null
        return when (JsonScalars.str(line, "type")) {
            "user" -> formatUser(message["content"])
            "assistant" -> formatAssistant(message["content"] as? JsonArray)
            else -> null
        }
    }

    private fun formatUser(content: kotlinx.serialization.json.JsonElement?): String? {
        val text = when (content) {
            is JsonPrimitive -> JsonScalars.str(content)?.trim().orEmpty()
            is JsonArray -> content.mapNotNull { it as? JsonObject }
                .filter { JsonScalars.str(it, "type") == "text" }
                .mapNotNull { JsonScalars.str(it, "text")?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString("\n")
            is JsonObject, null -> ""
        }
        return text.takeIf(String::isNotEmpty)?.let { "User: $it" }
    }

    private fun formatAssistant(blocks: JsonArray?): String? {
        if (blocks == null) return null
        val parts = blocks.mapNotNull { it as? JsonObject }.mapNotNull { block ->
            when (JsonScalars.str(block, "type")) {
                "text" -> JsonScalars.str(block, "text")?.trim()?.takeIf(String::isNotEmpty)
                "tool_use" -> {
                    val name = JsonScalars.str(block, "name").orEmpty()
                    val input = block["input"] as? JsonObject
                    if (name.isEmpty()) null else "[Tool $name: ${input?.let(JsonWire::string) ?: "{}"}]"
                }
                else -> null
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")?.let { "Assistant: $it" }
    }
}

internal const val ANTIGRAVITY_MODEL_PREFIX: String = "claude-antigravity--"
private const val HANDOFF_HEADER = "Conversation so far before switching to Gemini:\n\n"

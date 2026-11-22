// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). agy's tool calls as the cards Claude
// Desktop draws: ACP names a call by its kind (execute, edit, read, search, fetch …), and Desktop draws a card by
// the name and input of a Claude Code tool, so each kind becomes the Claude tool that does the same thing, its input
// read from the call's diff, locations and raw input, with the call's title where agy gave nothing more. A kind with
// no Claude tool keeps agy's own name and input. A call's result is its text content, a diff said as the file it
// changed, or its raw output.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

/** One subagent delegation carried on an ACP tool call (`invoke_subagent`). */
internal data class SubagentCall(val description: String, val prompt: String, val subagentType: String)

/** One tool card: the Claude tool's [name], its [input], and agy's [title] for it. */
internal data class ToolCard(
    val name: String,
    val input: JsonObject,
    val title: String,
    val subagent: SubagentCall? = null,
)

/** Builds the card and the result of an ACP tool call. */
internal class ToolCards(
    private val mcpLookup: McpToolLookup = object : McpToolLookup {
        override fun claudeName(rawName: String): String? = null
    },
) {
    /** The card of [call], an ACP `tool_call` (or the `toolCall` of a permission request). */
    fun of(call: JsonObject): ToolCard {
        val facts = CallFacts(call)
        return subagentOf(call, facts) ?: mcpOf(call, facts) ?: byKind(call, facts)
    }

    private fun byKind(call: JsonObject, facts: CallFacts): ToolCard = when (JsonScalars.str(call, "kind")) {
        "execute" -> facts.card("Bash", "command" to facts.text(COMMAND_KEYS), DESCRIPTION_KEY to facts.title)
        "edit" -> edit(facts)
        "read" -> facts.card("Read", TOOL_FILE_PATH to facts.where)
        "search" -> facts.card("Grep", "pattern" to facts.text(PATTERN_KEYS), "path" to facts.path)
        "fetch" -> facts.card("WebFetch", "url" to facts.text(URL_KEYS), "prompt" to facts.title)
        else -> ToolCard(
            JsonScalars.str(call, "name") ?: facts.title.ifEmpty { OTHER_TOOL },
            facts.raw.ifEmpty { buildJsonObject { put(DESCRIPTION_KEY, facts.title) } },
            facts.title,
        )
    }

    private fun mcpOf(call: JsonObject, facts: CallFacts): ToolCard? {
        val claudeName = JsonScalars.str(call, "name")?.let(mcpLookup::claudeName) ?: return null
        return ToolCard(
            claudeName,
            facts.raw.ifEmpty { buildJsonObject { put(DESCRIPTION_KEY, facts.title) } },
            facts.title,
        )
    }

    /** Translates agy's `invoke_subagent` delegation into Claude Code's `Task` card so Desktop draws a subagent pane
     *  and agy's own tool name never reaches the wire or transcript. */
    private fun subagentOf(call: JsonObject, facts: CallFacts): ToolCard? {
        if (JsonScalars.str(call, "name") != SUBAGENT_TOOL) return null
        val role = JsonScalars.str(facts.raw, SUBAGENT_ROLE).orEmpty()
        val prompt = JsonScalars.str(facts.raw, SUBAGENT_PROMPT).orEmpty()
        val typeName = JsonScalars.str(facts.raw, SUBAGENT_TYPE).orEmpty()
        val input = buildJsonObject {
            put(DESCRIPTION_KEY, role)
            put("prompt", prompt)
            if (typeName.isNotEmpty()) put("subagent_type", typeName)
        }
        return ToolCard(TASK_TOOL, input, role.ifEmpty { TASK_TOOL }, SubagentCall(role, prompt, typeName))
    }

    /** What [call] (its `tool_call_update`) came to, as Desktop shows a tool's result. */
    fun resultText(call: JsonObject): String {
        val parts = CallFacts(call).contents.mapNotNull { content ->
            when (JsonScalars.str(content, "type")) {
                TOOL_CONTENT -> JsonScalars.str(content[TOOL_CONTENT] as? JsonObject, "text")
                TOOL_DIFF -> "Updated ${JsonScalars.str(content, "path").orEmpty()}"
                else -> null
            }
        }
        if (parts.isNotEmpty()) return parts.joinToString("\n")
        return when (val output = call["rawOutput"]) {
            null -> ""
            is JsonPrimitive -> JsonScalars.str(output).orEmpty()
            is JsonObject, is JsonArray -> JsonWire.string(output)
        }
    }

    /** An edit with a diff that has no old text writes a new file; any other is an edit of one. */
    private fun edit(facts: CallFacts): ToolCard {
        val diff = facts.diff
        val file = JsonScalars.str(diff, "path") ?: facts.where
        val after = JsonScalars.str(diff, "newText").orEmpty()
        val before = JsonScalars.str(diff, "oldText")
        return if (diff != null && before == null) {
            facts.card("Write", TOOL_FILE_PATH to file, TOOL_CONTENT to after)
        } else {
            facts.card("Edit", TOOL_FILE_PATH to file, "old_string" to before.orEmpty(), "new_string" to after)
        }
    }
}

/** What an ACP tool call says of itself: its title, raw input, the path it touches and its diff. */
private class CallFacts(call: JsonObject) {
    val title: String = JsonScalars.str(call, "title").orEmpty()
    val raw: JsonObject = call["rawInput"] as? JsonObject ?: JsonObject(emptyMap())
    val path: String? = JsonScalars.str((call["locations"] as? JsonArray)?.firstOrNull() as? JsonObject, "path")
        ?: text(PATH_KEYS, null)
    val contents: List<JsonObject> = (call[TOOL_CONTENT] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val diff: JsonObject? = contents.firstOrNull { JsonScalars.str(it, "type") == TOOL_DIFF }

    /** The path, or the title where the call names none. */
    val where: String get() = path ?: title

    /** A card for Claude tool [name] whose input is [fields], those with a value. */
    fun card(name: String, vararg fields: Pair<String, String?>): ToolCard =
        ToolCard(name, buildJsonObject { fields.forEach { (key, value) -> if (value != null) put(key, value) } }, title)

    /** The first of [keys] the raw input has, as text (a list, a command's words, joined by spaces); [otherwise] when
     *  it has none. */
    fun text(keys: List<String>, otherwise: String? = title): String? = keys.firstNotNullOfOrNull { key ->
        when (val value: JsonElement? = raw[key]) {
            is JsonArray -> value.mapNotNull(JsonScalars::str).joinToString(" ").ifEmpty { null }
            is JsonPrimitive -> JsonScalars.str(value)?.ifEmpty { null }
            is JsonObject, null -> null
        }
    } ?: otherwise
}

/** The Claude tools agy's calls are drawn as, which `system/init` lists. */
internal val claudeToolNames: List<String> = listOf("Bash", "Edit", "Write", "Read", "Grep", "WebFetch", "Task")

/** A call with no kind Desktop knows, no name and no title. */
private const val OTHER_TOOL = "Tool"
private const val SUBAGENT_TOOL = "invoke_subagent"
private const val TASK_TOOL = "Task"
private const val SUBAGENT_PROMPT = "Prompt"
private const val SUBAGENT_ROLE = "Role"
private const val SUBAGENT_TYPE = "TypeName"

private const val TOOL_FILE_PATH = "file_path"
private const val TOOL_CONTENT = "content"
private const val TOOL_DIFF = "diff"
private const val DESCRIPTION_KEY = "description"

// Where agy's raw input may carry each value. Not yet seen from agy itself (unverified), so each lists the spellings
// ACP agents use; the call's title stands in when none is there.
private val COMMAND_KEYS = listOf("command", "cmd", "CommandLine")
private val PATH_KEYS = listOf("path", "file_path", "filePath", "AbsolutePath")
private val PATTERN_KEYS = listOf("pattern", "query", "Query")
private val URL_KEYS = listOf("url", "Url")

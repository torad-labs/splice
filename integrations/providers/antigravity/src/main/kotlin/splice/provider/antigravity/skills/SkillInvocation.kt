// NEW: desktop-antigravity (ACP skills). A typed `/name args` that names a local skill or command agy does not own,
// expanded into the prompt agy reads, the way Claude Code expands one: the command's name and arguments, the skill's
// folder (so relative `scripts/` and `references/` paths resolve), then its body with `$ARGUMENTS` filled in. Only what
// agy reads changes; Desktop's replay and the transcript keep what the person typed. agy's own commands, `/login` and
// `/splice:login` go through as typed.
package splice.provider.antigravity.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.provider.antigravity.TEXT_BLOCK

/** Expands Desktop message content that invokes a local skill or command. */
internal class SkillInvocation {
    /** [content] with its first text expanded when it invokes one of [items] whose name is not in [native]; else
     *  [content] as it was. Every other block (an image, say) stays where it was. */
    fun expand(content: JsonElement, items: List<LocalSkillItem>, native: Set<String>): JsonElement = when (content) {
        is JsonPrimitive -> JsonScalars.str(content)?.let { expanded(it, items, native) }?.let { JsonPrimitive(it) }
            ?: content
        is JsonArray -> expandBlocks(content, items, native)
        is JsonObject -> content
    }

    private fun expandBlocks(blocks: JsonArray, items: List<LocalSkillItem>, native: Set<String>): JsonArray {
        val at = blocks.indexOfFirst { JsonScalars.str(it as? JsonObject, "type") == TEXT_BLOCK }
        val block = blocks.getOrNull(at) as? JsonObject ?: return blocks
        val text = expanded(JsonScalars.str(block, TEXT_BLOCK).orEmpty(), items, native) ?: return blocks
        val replaced = blocks.toMutableList()
        replaced[at] = JsonObject(block + (TEXT_BLOCK to JsonPrimitive(text)))
        return JsonArray(replaced)
    }

    /** [typed] expanded, or null when it invokes no local skill or command that agy lacks. `/login` and
     *  `/splice:login` are splice's own and never expand, whatever a local command of that name says. */
    private fun expanded(typed: String, items: List<LocalSkillItem>, native: Set<String>): String? {
        val match = INVOCATION.matchEntire(typed.trim()) ?: return null
        val name = match.groupValues[1]
        val item = items.firstOrNull { it.name == name }?.takeUnless { name in native || name in RESERVED }
        return item?.let { prompt(it, match.groupValues[2].trim()) }
    }

    private fun prompt(item: LocalSkillItem, args: String): String {
        val header = listOfNotNull(
            "<command-name>/${item.name}</command-name>",
            "<command-args>$args</command-args>",
            item.folder?.let { "Base directory for this skill: $it" },
        )
        val body = item.body.trim('\n', '\r')
        val filled = when {
            ARGUMENTS in body -> body.replace(ARGUMENTS, args)
            args.isEmpty() -> body
            else -> listOf(body, "ARGUMENTS: $args").filter(String::isNotEmpty).joinToString("\n\n")
        }
        return (header.joinToString("\n") + "\n\n" + filled).trimEnd()
    }
}

private const val ARGUMENTS = "\$ARGUMENTS"

/** The commands splice answers itself (AcpLoginPrompt), which no local command of the same name takes over. */
private val RESERVED = setOf("login", "splice:login")
private val INVOCATION = Regex("/(\\S+)(?:\\s+([\\s\\S]*))?")

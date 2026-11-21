// NEW: a mode's tools as its folder writes them (modes/README.md): tools/<tool>.json, the input schema as written;
// tools/<tool>.md, the description, trimmed; and tools/<tool>.meta.json, the tool's _meta, when it is there.
package splice.modes.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads each listed tool of [folder]; [fault] hears every missing or malformed file, named. */
internal class ToolsRead(private val folder: ModeFolder, private val fault: ModeFaults) {
    /** The tools that are whole, in the manifest's order. */
    fun tools(names: List<String>, server: String): List<ModeTool> =
        names.filter(ModeNames::isToolName).mapNotNull { tool(it, server) }

    private fun tool(name: String, server: String): ModeTool? {
        val before = fault.size
        val schema = schema(name)
        val description = description(name)
        val meta = meta(name)
        val offered = ModeNames.offered(server, name)
        if (offered.length > ModeNames.MAX_OFFERED) {
            fault.report("tool $name is offered as $offered, longer than ${ModeNames.MAX_OFFERED} characters")
        }
        // Each part that reads as null has reported why, so a tool with no new finding has them all.
        if (fault.size > before) return null
        return ModeTool(name, checkNotNull(description), checkNotNull(schema), meta, offered)
    }

    private fun schema(name: String): JsonObject? {
        val file = "tools/$name.json"
        val text = folder.read(file) ?: return null.also { fault.report("tools lists $name, and $file is missing") }
        val schema = ModeJson.parse(text) as? JsonObject
        val typed = (schema?.get("type") as? JsonPrimitive)?.content == "object"
        if (!typed) fault.report("$file is not a JSON Schema object with type object")
        return schema.takeIf { typed }
    }

    private fun description(name: String): String? {
        val file = "tools/$name.md"
        val text = folder.read(file)?.trim()
            ?: return null.also { fault.report("tools lists $name, and $file is missing") }
        return text.ifEmpty { null.also { fault.report("$file is empty") } }
    }

    private fun meta(name: String): JsonObject? {
        val file = "tools/$name.meta.json"
        val text = folder.read(file) ?: return null
        return ModeJson.parse(text) as? JsonObject ?: null.also { fault.report("$file is not a JSON object") }
    }
}

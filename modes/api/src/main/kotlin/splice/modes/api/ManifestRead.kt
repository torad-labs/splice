// NEW: mode.toml's own keys read and checked (modes/README.md, "The manifest"), every fault at once.
package splice.modes.api

import kotlinx.serialization.json.JsonObject

private val TOP_KEYS =
    setOf("name", "summary", "server", "handler", "tools", "kind", "on", "heads", "callers", "resources")
private const val NAME_RULE = "[a-z][a-z0-9]*"
private const val TOOL_RULE = "[a-z][a-z0-9_]*, with no __"

/** The manifest of the mode in folder [folder], or null when a key is missing or wrong; [fault] hears each fault. */
internal class ManifestRead(
    private val toml: JsonObject,
    private val folder: String,
    private val fault: ModeFaults,
) {
    private val keys = ManifestKeys(toml, fault)

    fun manifest(): ModeManifest? {
        keys.unknown(TOP_KEYS)
        val name = keys.string("name")?.also(::checkName)
        val summary = keys.string("summary")
        val server = keys.string("server")?.also(::checkServer)
        val handler = keys.string("handler")
        val tools = keys.strings("tools")?.also { list -> list.forEach(::checkTool) }
        val kind = kind()
        val offer = offer()
        // Every key that reads as null has reported why, so a manifest with no finding has all of them.
        if (fault.any()) return null
        return ModeManifest(
            checkNotNull(name),
            checkNotNull(summary),
            checkNotNull(server),
            checkNotNull(handler),
            checkNotNull(tools),
            checkNotNull(kind),
            offer,
        )
    }

    private fun checkName(name: String) {
        if (name != folder) fault.report("name is not the folder's name, $folder")
        if (!ModeNames.isModeName(name)) fault.report("name is not a mode name: $NAME_RULE")
    }

    private fun checkServer(server: String) {
        if (!ModeNames.isToolName(server)) fault.report("server is not a server name: $TOOL_RULE")
    }

    private fun checkTool(tool: String) {
        if (!ModeNames.isToolName(tool)) fault.report("tools lists $tool, which is not a tool name: $TOOL_RULE")
    }

    private fun kind(): ModeKind? {
        val word = keys.optionalString("kind") ?: return if ("kind" in toml) null else ModeKind.DOOR
        return ModeKind.entries.firstOrNull { it.wire == word }
            ?: null.also { fault.report("kind is neither door nor mcp") }
    }

    private fun offer(): ModeOffer {
        val on = keys.boolean("on")
        val heads = keys.table("heads", setOf("only", "off"))
        val callers = keys.table("callers", setOf("off"))
        val words = callers?.strings("off", required = false).orEmpty()
        val callersOff = words.mapNotNull { word -> ModeCaller.entries.firstOrNull { it.wire == word } }
        if (callersOff.size < words.size) fault.report("callers.off names a caller that is not door, head or desktop")
        val only = heads?.strings("only", required = false).orEmpty()
        val off = heads?.strings("off", required = false).orEmpty()
        return ModeOffer(on ?: false, only, off, callersOff)
    }
}

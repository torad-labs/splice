// NEW: a mode's [[resources]] (modes/README.md): each a ui:// app an mcp mode serves, with its content in resources/
// or given by its handler. A door mode has none.
package splice.modes.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val RESOURCE_KEYS = setOf("uri", "name", "mimeType", "file", "_meta")

/** Reads the resources of [folder]; [fault] hears every fault, named by its key or file. */
internal class ResourcesRead(private val folder: ModeFolder, private val fault: ModeFaults) {
    fun resources(element: JsonElement?, kind: ModeKind?): List<ModeResource> {
        val tables = tables(element) ?: return emptyList()
        if (kind == ModeKind.DOOR && tables.isNotEmpty()) {
            fault.report("resources are declared, and a door mode has none: only kind mcp may")
            return emptyList()
        }
        return tables.mapIndexedNotNull { at, table -> resource(table, at + 1) }
    }

    private fun tables(element: JsonElement?): List<JsonObject>? {
        if (element == null) return null
        val tables = (element as? JsonArray)?.filterIsInstance<JsonObject>()
        if (tables == null || tables.size != element.size) {
            fault.report("resources is not a list of [[resources]] tables")
        }
        return tables
    }

    private fun resource(table: JsonObject, at: Int): ModeResource? {
        val before = fault.size
        val keys = ManifestKeys(table, fault, "resources[$at].")
        keys.unknown(RESOURCE_KEYS)
        val uri = keys.string("uri")?.takeIf(::isApp)
        val name = keys.optionalString("name")
        val mimeType = keys.string("mimeType")
        val meta = keys.json("_meta")
        val file = keys.optionalString("file")
        val content = file?.let(::content)
        // Each key that reads as null has reported why, so a resource with no new finding has them all.
        if (fault.size > before) return null
        val app = checkNotNull(uri)
        return ModeResource(app, name ?: app.substringAfterLast('/'), checkNotNull(mimeType), meta, content)
    }

    private fun isApp(uri: String): Boolean =
        uri.startsWith("ui://") || false.also { fault.report("resources[].uri is not a ui:// uri") }

    private fun content(file: String): String? =
        folder.read("resources/$file") ?: null.also { fault.report("resources/$file is missing") }
}

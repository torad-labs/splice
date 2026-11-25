// NEW: `GET /api/visual/view?uri=<ui://…>` (modes/visual): a view the manifest declares, as `resources/read` gives it
// on the mode's MCP server, `{ html, meta }`, `meta` its `_meta`. Read from the mode's own manifest and resources as
// the jar carries them (the splice.mode plugin lays them under splice-modes/visual/), so the route and the MCP server
// give the same bytes from the one manifest.
package splice.modes.visual

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.capability.TomlTree
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse

private const val VISUAL_FILES = "splice-modes/visual"

internal class VisualView {
    private val views: Map<String, View> by lazy(::read)

    /** The view named by the query's `uri`, or 404 for one the manifest does not declare. */
    fun answer(request: ModeRequest): ModeResponse {
        val view = request.query["uri"]?.let(views::get)
            ?: return ModeResponse(
                HttpStatus.NOT_FOUND,
                buildJsonObject { put("error", "the visual mode has no such view") },
            )
        return ModeResponse(
            HttpStatus.OK,
            buildJsonObject {
                put("html", view.html)
                put("meta", view.meta)
            },
        )
    }

    /** Every `[[resources]]` entry with a file, by its uri. */
    private fun read(): Map<String, View> {
        val manifest = file("mode.toml")?.let(TomlTree::read) ?: return emptyMap()
        val tables = (manifest["resources"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return tables.mapNotNull { table ->
            val uri = JsonScalars.str(table, "uri")
            val html = JsonScalars.str(table, "file")?.let { file("resources/$it") }
            val meta = table["_meta"] as? JsonObject ?: JsonObject(emptyMap())
            if (uri == null || html == null) null else uri to View(html, meta)
        }.toMap()
    }

    private fun file(path: String): String? =
        ClassLoader.getSystemResourceAsStream("$VISUAL_FILES/$path")?.use { it.readAllBytes().decodeToString() }

    private data class View(val html: String, val meta: JsonObject)
}

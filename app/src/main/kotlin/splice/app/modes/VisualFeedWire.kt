package splice.app.modes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.WireJson
import splice.control.mcp.inprocess.McpDocumentReader
import splice.modes.api.ModeRoute

/** The visual ingress owns decoding its feed values; unrelated mode routes and servers keep their parser. */
internal object VisualFeedWire : McpDocumentReader {
    private const val MODE = "visual"
    private const val PATH = "/api/visual/feeds/{name}"

    fun isVisual(name: String): Boolean = name == MODE

    fun isPut(mode: String, route: ModeRoute): Boolean =
        isVisual(mode) && route.method == "PUT" && route.path == PATH

    override fun invoke(document: String): JsonObject? {
        val message = WireJson.read(document) as? JsonObject ?: return null
        // Only scalar ids may reach the host's reply encoder, which echoes the id.
        return message.takeIf { it["id"] == null || it["id"] is JsonPrimitive }
    }
}

// NEW: a mode as `GET /api/modes` and `PUT /api/modes/<name>` answer it (modes/README.md): what its folder says, and
// whether it is on, for every head and per head, with where that value came from.
package splice.app.modes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.modes.api.FoundMode
import splice.modes.api.ModeState

internal class ModeRows(private val modes: DaemonModes) {
    /** `{modes:[...]}`, sorted by name. */
    fun all(): JsonObject = buildJsonObject {
        put("modes", JsonArray(modes.catalog.modes.sortedBy { it.name }.map(::row)))
    }

    fun row(mode: FoundMode): JsonObject = buildJsonObject {
        val manifest = mode.manifest
        put("name", mode.name)
        put("summary", manifest.summary)
        put("kind", manifest.kind.wire)
        put("server", manifest.server)
        put("prompt", mode.prompt != null)
        put("tools", tools(mode))
        put("resources", resources(mode))
        put("callers_off", buildJsonArray { manifest.offer.callersOff.forEach { add(it.wire) } })
        put("on", state(modes.everyHead(mode), "value"))
        putJsonObject("heads") { modes.heads.forEach { put(it, state(modes.state(mode, it), "on")) } }
    }

    private fun tools(mode: FoundMode) = buildJsonArray {
        mode.tools.forEach { tool ->
            addJsonObject {
                put("name", tool.name)
                put("offered_as", tool.offeredAs)
                put("description", tool.description)
                tool.meta?.let { put("_meta", it) }
            }
        }
    }

    private fun resources(mode: FoundMode) = buildJsonArray {
        mode.resources.forEach { resource ->
            addJsonObject {
                put("uri", resource.uri)
                put("name", resource.name)
                put("mimeType", resource.mimeType)
                put("_meta", resource.meta ?: JsonObject(emptyMap()))
            }
        }
    }

    private fun state(state: ModeState, key: String) = buildJsonObject {
        put(key, state.on)
        put("from", state.from.wire)
    }
}

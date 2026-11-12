package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec

/** Reads the current shared declarations' exact retained tuples; it never starts or contacts a holder. */
internal class HubSharedCatalog(private val listings: HubListings, private val tuple: HubTuple) {
    fun read(specs: Map<String, McpServerSpec>): Map<String, JsonObject> = specs.mapNotNull { (name, spec) ->
        listings.tools(tuple.digest(spec))?.let { name to it }
    }.toMap()
}

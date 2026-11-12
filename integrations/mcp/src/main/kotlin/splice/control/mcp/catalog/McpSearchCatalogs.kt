package splice.control.mcp.catalog

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpSharing
import splice.control.mcp.GlobalMcpServers
import splice.control.mcp.hosted.HostedSharedCatalog
import splice.control.mcp.hub.McpHubCalls

/** Observes current, explicitly shared catalogs while retaining unknown configured names for identity checks. */
public class McpSearchCatalogs internal constructor(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val hosted: HostedSharedCatalog,
    private val hub: McpHubCalls?,
) {
    public fun sharedTools(headKey: String): Map<String, JsonObject> = searchCatalogs(headKey)
        .mapNotNull { (name, tools) -> tools?.let { name to it } }.toMap()

    /** One configuration read binds every raw name; an unavailable, conflicting or nonshared catalog is unknown. */
    public fun searchCatalogs(headKey: String): Map<String, JsonObject?> {
        val inputs = global.inputs()
        val declared = inputs.groupBy { it.name }
        val specs = sharing.plan(inputs, headKey).hosted.filterKeys { declared.getValue(it).size == 1 }
        val observed = McpCatalogSnapshots.combine(
            specs.keys,
            hosted.read(specs),
            hub?.sharedTools(specs).orEmpty(),
        )
        return declared.keys.associateWith(observed::get)
    }
}

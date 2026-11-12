package splice.app.provider.codex

import splice.control.mcp.McpHost
import splice.provider.codex.catalog.CodeModeMcpSearchCatalog

/** Heads are built before the host; their suppliers observe the host attached before control listeners bind. */
internal class CodeModeMcpCatalogs {
    @Volatile private var host: McpHost? = null

    fun attach(host: McpHost) {
        this.host = host
    }

    fun forHead(headKey: String): CodeModeMcpSearchCatalog = CodeModeSharedMcpSearch {
        host?.catalogs?.searchCatalogs(headKey).orEmpty()
    }
}

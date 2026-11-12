package splice.provider.codex.catalog

import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.search.CodeModeMcpSearchInfo

/** A head-owned immutable snapshot, limited to exact advertised tools whose transport provenance is established. */
public fun interface CodeModeMcpSearchCatalog {
    public fun entries(tools: List<CodeModeManual.NestedTool>): Map<String, CodeModeMcpSearchInfo>
}

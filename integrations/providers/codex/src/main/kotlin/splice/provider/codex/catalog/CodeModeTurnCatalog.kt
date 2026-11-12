package splice.provider.codex.catalog

import splice.provider.codex.CodeModeToolPolicy
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.search.CodeModeMcpSearchInfo

/** The executable catalog and its policy belong to the same model step. */
public data class CodeModeTurnCatalog(
    val definitions: List<CodeModeManual.NestedTool> = emptyList(),
    val policy: CodeModeToolPolicy = CodeModeToolPolicy(),
    val descriptions: Map<String, String> = emptyMap(),
    val mcpSearch: Map<String, CodeModeMcpSearchInfo> = emptyMap(),
)

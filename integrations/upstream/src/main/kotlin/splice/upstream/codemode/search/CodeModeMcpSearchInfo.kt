package splice.upstream.codemode.search

/** Transport-proved identities; callers never recover these by splitting a flattened declaration name. */
public data class CodeModeMcpSearchIdentity(
    val flatName: String,
    val callableName: String,
    val rawToolName: String,
    val serverName: String,
)

/** Raw catalog documentation, before MCP search applies its own trimming and plugin namespace cap. */
public data class CodeModeMcpSearchDescriptions(
    val title: String? = null,
    val description: String? = null,
    val connectorName: String? = null,
    val namespaceDescription: String? = null,
    val pluginDisplayNames: List<String> = emptyList(),
)

/** A custom search source accompanies its ordinary tool spec, as McpHandler::search_info does in Codex. */
public data class CodeModeMcpSearchInfo(
    val identity: CodeModeMcpSearchIdentity,
    val descriptions: CodeModeMcpSearchDescriptions = CodeModeMcpSearchDescriptions(),
    val inputPropertyNames: List<String> = emptyList(),
    val agentPlugin: Boolean = false,
    /** Exact advertised spec bound by the supplier; the source's flat search spelling may preserve extra underscores. */
    val declarationName: String = identity.flatName,
)

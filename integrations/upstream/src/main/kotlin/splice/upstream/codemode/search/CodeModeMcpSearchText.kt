package splice.upstream.codemode.search

import splice.upstream.codemode.CodeModeText

/** PORT-OF: immutable Codex 0.165 core/tools/handlers/mcp.rs::build_mcp_search_text. */
public object CodeModeMcpSearchText {
    public fun build(info: CodeModeMcpSearchInfo): String {
        val identity = info.identity
        val parts = mutableListOf(identity.flatName, identity.callableName, identity.rawToolName, identity.serverName)
        val descriptions = info.descriptions
        append(parts, descriptions.title)
        append(parts, descriptions.description)
        append(parts, descriptions.connectorName)
        val namespace = descriptions.namespaceDescription?.let { if (info.agentPlugin) pluginPrefix(it) else it }
        append(parts, namespace)
        descriptions.pluginDisplayNames.forEach { append(parts, it) }
        parts += info.inputPropertyNames.sortedWith(CodeModeText.order)
        return parts.joinToString(" ")
    }

    private fun append(parts: MutableList<String>, value: String?) {
        val trimmed = value?.let(CodeModeText::trim)
        if (!trimmed.isNullOrEmpty()) parts += trimmed
    }

    private fun pluginPrefix(text: String): String {
        val bytes = text.encodeToByteArray()
        if (bytes.size <= MCP_PLUGIN_NAMESPACE_BYTES) return text
        var end = MCP_PLUGIN_NAMESPACE_BYTES
        while (bytes[end].toInt() and MCP_UTF8_CONTINUATION_MASK == MCP_UTF8_CONTINUATION_TAG) end--
        return bytes.decodeToString(0, end)
    }
}

// why: core/tools/handlers/mcp.rs limits agent-plugin namespace descriptions to 1,000 UTF-8 bytes before trim.
private const val MCP_PLUGIN_NAMESPACE_BYTES = 1_000

// why: UTF-8 continuation bytes start with binary 10, so a byte prefix cannot end inside a scalar value.
private const val MCP_UTF8_CONTINUATION_MASK = 0xc0
private const val MCP_UTF8_CONTINUATION_TAG = 0x80

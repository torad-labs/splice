package splice.client.mcp.names

/** Claude Code 2.1.312's Sn/Io MCP naming rules, including its special `claude.ai ` prefix handling.
 * Actual server/tool identities remain unchanged in the transport receipt. */
public object ClaudeMcpToolNames {
    private val underscores = Regex("_+")

    public fun name(server: String, tool: String): String = "mcp__${part(server)}__${part(tool)}"

    private fun part(value: String): String {
        // The source regex has no Unicode flag: each UTF-16 surrogate is replaced independently.
        val ascii = buildString(value.length) { value.forEach { append(character(it)) } }
        return if (value.startsWith("claude.ai ")) ascii.replace(underscores, "_").trim('_') else ascii
    }

    private fun character(value: Char): Char = when (value) {
        in 'a'..'z', in 'A'..'Z', in '0'..'9', '_', '-' -> value
        else -> '_'
    }
}

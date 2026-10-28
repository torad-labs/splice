package splice.upstream.codemode.search

/** Exact unchanged subset of Codex 0.165's regular, prefixed MCP tool construction. */
public object CodeModeMcpSearchNames {
    /**
     * All configured regular servers must be present; null means their tool list is unknown. A source rename,
     * possible collision or unknown matching namespace returns null, so metadata never guesses an identity.
     * Codex Apps namespaces and non-prefixed source modes require their own transport proof.
     */
    public fun regularUnchanged(
        server: String,
        tool: String,
        allRegularTools: Map<String, List<String>?>,
    ): CodeModeMcpSearchIdentity? {
        val target = target(server, tool) ?: return null
        val members = allRegularTools[server] ?: return null
        val uniqueMember = members.count { it == tool } == 1 &&
            members.none { it != tool && sanitize(it) == tool }
        val namespace = namespace(server)
        val uniqueNamespace = allRegularTools.none { (other, names) ->
            if (other == server || names?.isEmpty() == true) false else namespace(other) == namespace
        }
        return target.takeIf {
            uniqueMember && uniqueNamespace && distinctConcatenation(
                server,
                target,
                allRegularTools,
            )
        }
    }

    private fun distinctConcatenation(
        server: String,
        target: CodeModeMcpSearchIdentity,
        allRegularTools: Map<String, List<String>?>,
    ): Boolean = allRegularTools.none { (other, names) ->
        if (other == server) {
            false
        } else {
            val otherNamespace = namespace(other)
            if (names == null) {
                target.flatName.startsWith(otherNamespace) && target.flatName.length > otherNamespace.length
            } else {
                names.any { otherNamespace + sanitize(it) == target.flatName }
            }
        }
    }

    private fun target(server: String, tool: String): CodeModeMcpSearchIdentity? {
        if (!unchanged(server) || !unchanged(tool)) return null
        val namespace = namespace(server)
        if (namespace == MCP_APPS_NAMESPACE || namespace.startsWith("${MCP_APPS_NAMESPACE}__")) return null
        val flat = namespace + tool
        // Source's unique_callable_parts compares the concatenation without a separator. Every hashed output
        // contains this suffix shape, so refusing it excludes accidental collisions with renamed prior entries.
        return if (flat.length + MCP_NAME_RESERVED_BYTES <= MCP_NAME_MAX_BYTES && !MCP_NAME_HASH.containsMatchIn(
                flat,
            )
        ) {
            CodeModeMcpSearchIdentity(flat, tool, tool, server)
        } else {
            null
        }
    }

    private fun unchanged(name: String): Boolean = name.isNotEmpty() && name.all(::allowed)

    private fun namespace(server: String): String = sanitize(server).let {
        if (it.startsWith("mcp__")) it else "mcp__$it"
    }

    private fun sanitize(name: String): String = buildString {
        name.codePoints().forEach { append(if (it in MCP_NAME_ASCII) it.toChar() else '_') }
    }.ifEmpty { "_" }

    private fun allowed(char: Char): Boolean = char.code in MCP_NAME_ASCII
}

// why: source MAX_TOOL_NAME_LENGTH=128 and unique_callable_parts reserves the two-byte "__" delimiter.
private const val MCP_NAME_MAX_BYTES = 128
private const val MCP_NAME_RESERVED_BYTES = 2

// why: codex_apps.rs derives connector-specific namespaces; regular raw-server identity cannot prove that route.
private const val MCP_APPS_NAMESPACE = "mcp__codex_apps"

// why: sanitizer implementation accepts ASCII alphanumeric and underscore; its doc's hyphen allowance is stale.
private val MCP_NAME_ASCII = (('a'..'z') + ('A'..'Z') + ('0'..'9') + '_').map { it.code }.toSet()

// why: source callable_name_hash_suffix is underscore plus the first 12 lowercase hex digits of SHA1.
private val MCP_NAME_HASH = Regex("_[a-f0-9]{12}")

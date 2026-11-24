// NEW: #791 (server mode, routing and clients). What the decode accepts in `[server.client]` and `[server.aliases]` but
// the listener could not act on: a version header that is no HTTP header name, an outdated status other than the three
// an older laptop can be answered with, and an alias that is no single path segment after a `/`, that shadows one of
// the listener's own routes, or that stands for nothing this server serves. Each is a boot finding beside every other,
// naming its key.
package splice.core.topology

import splice.core.topology.toml.TomlKeys
import splice.core.wire.HttpStatus

internal class ServerRoutingChecks(private val server: ServerConfig) {
    /** An HTTP header name: one or more of RFC 9110's token characters. */
    private val token = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")

    /** One path segment of letters, digits and `-._~`, after its `/`. */
    private val segment = Regex("""^[A-Za-z0-9._~-]+$""")

    private val keys = TomlKeys()

    fun findings(): List<TopologyFinding> = client() + aliases()

    private fun client(): List<TopologyFinding> {
        val client = server.client
        val header = client.versionHeader.trim()
        return listOfNotNull(
            TopologyFinding("$CLIENT_TABLE.version_header", "must be an HTTP header name, as in \"x-team-launcher\"")
                .takeIf { header.isNotEmpty() && !token.matches(header) },
            TopologyFinding(
                "$CLIENT_TABLE.outdated_status",
                "must be 200 (told in the conversation), 400 or 426 (Upgrade Required)",
            ).takeIf { client.outdatedStatus !in OUTDATED_STATUSES },
        )
    }

    private fun aliases(): List<TopologyFinding> = server.aliases.entries.mapNotNull { (raw, value) ->
        val prefix = keys.unquote(raw).trim()
        alias(prefix, prefix.removePrefix("/"), value.trim())?.let { TopologyFinding(ALIASES_TABLE, it) }
    }

    /** What is wrong with the alias [prefix], whose one segment is [name], standing for [target]; null when nothing. */
    private fun alias(prefix: String, name: String, target: String): String? = when {
        !prefix.startsWith("/") || !segment.matches(name) ->
            "'$prefix' must be one path segment after a '/', as in \"/v1\""
        name in RESERVED -> "'$prefix' is one of the listener's own routes and cannot be an alias"
        name in server.commands || name == DOOR_SEGMENT ->
            "'$prefix' is already a path this server answers and cannot be an alias"
        target != DOOR_SEGMENT && target !in server.commands ->
            "'$prefix' must stand for \"$DOOR_SEGMENT\" or a command [server] commands lists, not '$target'"
        else -> null
    }
}

// The listener's own first segments, which no alias may take.
private val RESERVED = setOf("server", "health")

// What `[server.client] outdated_status` may say: 200 in the conversation, or a refusal Claude Code shows.
private val OUTDATED_STATUSES = setOf(OUTDATED_IN_CONVERSATION, HttpStatus.BAD_REQUEST, HttpStatus.UPGRADE_REQUIRED)

private const val CLIENT_TABLE = "server.client"
private const val ALIASES_TABLE = "server.aliases"

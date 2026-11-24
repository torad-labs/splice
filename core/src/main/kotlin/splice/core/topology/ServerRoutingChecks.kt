// NEW: #791 (server mode, routing and clients). What the decode accepts in `[server.client]` and `[server.aliases]` but
// the listener could not act on: a version header that is no HTTP header name, an outdated status other than the three
// an older laptop can be answered with, and an alias that is no single path segment after a `/`, that is written twice
// once its quotes and spaces are gone, that shadows one of the listener's own routes or a name a served head answers to
// (its head key or its command), or that stands for nothing a head here serves. A provider served with no head is
// served at the team's door alone, so an alias reaches it as "door". Each is a boot finding beside every other, naming
// its key. No alias stands for one of the listener's own routes either, even where a head's command is so named: the
// listener answers that segment itself and never reaches the head.
package splice.core.topology

import splice.core.topology.toml.TomlKeys
import splice.core.wire.HttpStatus

internal class ServerRoutingChecks(private val topology: Topology) {
    private val server = topology.server

    /** An HTTP header name: one or more of RFC 9110's token characters. */
    private val token = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")

    /** One path segment of letters, digits and `-._~`, after its `/`. */
    private val segment = Regex("""^[A-Za-z0-9._~-]+$""")

    private val keys = TomlKeys()

    /** Every first segment a served head answers at, as the listener's catalog names it: by its command and by its head
     *  key, whichever of the two `[server] commands` lists. */
    private val headNames: Set<String> = topology.heads
        .filter { (key, head) -> (head.claude.command ?: key) in server.commands || key in server.commands }
        .flatMap { (key, head) -> listOf(key, head.claude.command ?: key) }
        .toSet()

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

    private fun aliases(): List<TopologyFinding> {
        val written = server.aliases.entries.map { (raw, value) -> keys.unquote(raw).trim() to value.trim() }
        val twice = written.groupBy { (prefix, _) -> prefix.removePrefix("/") }.filterValues { it.size > 1 }
            .map { (name, all) ->
                val spelled = all.joinToString(", ") { (prefix, _) -> "'$prefix'" }
                "'/$name' is written more than once ($spelled); keep one"
            }
        val each = written.mapNotNull { (prefix, target) -> alias(prefix, prefix.removePrefix("/"), target) }
        return (twice + each).map { TopologyFinding(ALIASES_TABLE, it) }
    }

    /** What is wrong with the alias [prefix], whose one segment is [name], standing for [target]; null when nothing. */
    private fun alias(prefix: String, name: String, target: String): String? =
        taken(prefix, name) ?: stands(prefix, target)

    /** Why the alias [prefix], whose one segment is [name], cannot be a path of its own; null when it can. */
    private fun taken(prefix: String, name: String): String? = when {
        !prefix.startsWith("/") || !segment.matches(name) ->
            "'$prefix' must be one path segment after a '/', as in \"/v1\""
        name in RESERVED -> "'$prefix' is one of the listener's own routes and cannot be an alias"
        name in server.commands || name in headNames || name == DOOR_SEGMENT ->
            "'$prefix' is already a path this server answers and cannot be an alias"
        else -> null
    }

    /** Why the alias [prefix] cannot stand for [target]; null when [target] is the door or a served head that
     *  is no route of the listener's own. */
    private fun stands(prefix: String, target: String): String? = when {
        target in RESERVED ->
            "'$prefix' stands for '$target', one of the listener's own routes, which no head is served at"
        target == DOOR_SEGMENT || target in headNames -> null
        target in server.commands ->
            "'$prefix' stands for '$target', a provider served with no head, which is served at the team's door " +
                "alone; write \"$DOOR_SEGMENT\""
        else ->
            "'$prefix' must stand for \"$DOOR_SEGMENT\" or a command [server] commands lists, not '$target'"
    }
}

// The listener's own first segments, which no alias may take or stand for.
private val RESERVED = setOf("server", "health")

// What `[server.client] outdated_status` may say: 200 in the conversation, or a refusal Claude Code shows.
private val OUTDATED_STATUSES = setOf(OUTDATED_IN_CONVERSATION, HttpStatus.BAD_REQUEST, HttpStatus.UPGRADE_REQUIRED)

private const val CLIENT_TABLE = "server.client"
private const val ALIASES_TABLE = "server.aliases"

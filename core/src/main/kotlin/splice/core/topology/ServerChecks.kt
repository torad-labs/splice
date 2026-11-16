// NEW: server mode (docs/specs/server-mode.md, decisions 1 to 3). What the decode accepts in `[server]` but the daemon
// may not serve: a server beside an enabled share, a command no head here runs, a command whose head signs with one
// person's plan, an address it cannot listen on, half a TLS pair, a proxy that is no address, an admin that is no
// email. Each is a boot finding beside every other, naming its key and never a value an operator wrote in confidence.
package splice.core.topology

import splice.core.listen.IpLiterals
import java.nio.file.Files
import java.nio.file.Path

internal class ServerChecks(
    private val topology: Topology,
    /** splice.toml's directory, which a relative PEM path resolves against; null leaves the files unread. */
    private val configDir: Path?,
) {
    private val server = topology.server

    fun findings(): List<TopologyFinding> =
        if (server.declared) listen() + exclusive() + commands() + tls() + people() + limits() else emptyList()

    private fun listen(): List<TopologyFinding> {
        val address = server.address
        val why = when {
            address == null -> "must be an address and a port to listen on, as in \"0.0.0.0:3443\""
            address.port !in validPortRange -> "its port is outside ${validPortRange.first}-${validPortRange.last}"
            else -> null
        }
        return listOfNotNull(why?.let { TopologyFinding(SERVER_LISTEN_OWNER, it) })
    }

    /** Decision 1: share answers anyone on the LAN and server answers only keys, so one daemon does one of them. */
    private fun exclusive(): List<TopologyFinding> = listOfNotNull(
        TopologyFinding(
            SERVER_TABLE,
            "[server] cannot run beside an enabled [share]: share mode answers anyone on the LAN and server mode " +
                "answers only keys; turn sharing off (`splice share off`) or remove [server]",
        ).takeIf { topology.share.enabled },
    )

    /** Decisions 2 and 3: every command is one a head here runs, on a provider that may serve a team. */
    private fun commands(): List<TopologyFinding> {
        val offered = topology.heads.map { (key, head) -> head.claude.command ?: key }.distinct()
        val has = offered.joinToString(", ").ifEmpty { "none" }
        val none = "list at least one command to serve; the commands here are $has".takeIf { server.commands.isEmpty() }
        val each = server.commands.mapNotNull { command ->
            val keys = topology.resolveHeadKeys(command)
            when {
                command == SERVER_TABLE ->
                    "no served command may be named '$SERVER_TABLE': that path on the listener is the server's own API"
                keys.isEmpty() -> "'$command' is not a command a head here runs; the commands here are $has"
                keys.size > 1 -> "'$command' names more than one head (${keys.joinToString(", ")}); name one"
                else -> personalPlan(command, keys.single())
            }
        }
        return (listOfNotNull(none) + each).map { TopologyFinding(SERVER_COMMANDS, it) }
    }

    /** Why [command], on head [key], may not be served to a team: its provider signs with a sign-in that is one
     *  person's plan. An API key or a self-hosted provider signs with nothing personal, and is served. */
    private fun personalPlan(command: String, key: String): String? {
        val kind = topology.heads[key]?.let { topology.providers[it.provider] }?.auth?.kind ?: return null
        val registered = AuthKindRegistry.from(kind)
        if (registered == null && !AuthKindRegistry.isOAuth(kind)) return null
        val login = registered?.signInLabel ?: kind
        return "'$command' signs with a personal plan login ($login), and such a plan's terms are for one person, so " +
            "it is not served to a team's keys; serve a command on an API-key or self-hosted provider"
    }

    private fun tls(): List<TopologyFinding> {
        val certSet = !server.tlsCert.isNullOrBlank()
        val keySet = !server.tlsKey.isNullOrBlank()
        val pair = "tls_cert and tls_key go together: set both to serve HTTPS, or neither behind a proxy that does"
        val half = listOfNotNull(
            TopologyFinding(SERVER_TLS_KEY, pair).takeIf { certSet && !keySet },
            TopologyFinding(SERVER_TLS_CERT, pair).takeIf { keySet && !certSet },
        )
        val dir = configDir ?: return half
        val pem = server.pem(dir) ?: return half
        val unread = "this file cannot be read; fix the path (a relative one is under splice.toml's directory) or " +
            "the file's permissions"
        val key = when {
            !Files.isReadable(pem.key) -> unread
            !pem.keyIsPkcs8() -> TLS_KEY_FORM
            else -> null
        }
        return listOfNotNull(
            TopologyFinding(SERVER_TLS_CERT, unread).takeUnless { Files.isReadable(pem.cert) },
            key?.let { TopologyFinding(SERVER_TLS_KEY, it) },
        )
    }

    private fun people(): List<TopologyFinding> {
        val proxy = "each entry must be an address or a prefix, as in \"10.0.0.0/8\""
        return listOfNotNull(
            TopologyFinding("$SERVER_TABLE.trusted_proxies", proxy)
                .takeUnless { server.trustedProxies.all { IpLiterals.prefix(it) != null } },
            TopologyFinding("$SERVER_TABLE.admins", "each entry must be a person's email")
                .takeUnless { server.admins.all { PersonEmails.read(it) != null } },
        )
    }

    private fun limits(): List<TopologyFinding> {
        val rule = "must be 0 (no cap) or more"
        return listOfNotNull(
            TopologyFinding("$SERVER_TABLE.limits.inflight_per_person", rule)
                .takeIf { server.limits.inflightPerPerson < 0 },
            TopologyFinding("$SERVER_TABLE.limits.rpm_per_person", rule).takeIf { server.limits.rpmPerPerson < 0 },
            TopologyFinding("$SERVER_TABLE.keepalive_seconds", "must be 0 (no keepalive) or a number of seconds")
                .takeIf { server.keepaliveSeconds < 0 },
        )
    }
}

private const val SERVER_TABLE = "server"
private const val SERVER_COMMANDS = "$SERVER_TABLE.commands"
private const val SERVER_TLS_CERT = "$SERVER_TABLE.tls_cert"
private const val SERVER_TLS_KEY = "$SERVER_TABLE.tls_key"

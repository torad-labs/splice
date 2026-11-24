// NEW: server mode (docs/specs/server-mode.md, decisions 1 to 3). What the decode accepts in `[server]` but the daemon
// may not serve: a server beside an enabled share, a command no head here runs, a command whose head signs with one
// person's plan, an address it cannot listen on, half a TLS pair, a proxy that is no address, an admin that is no
// email, a minimum client (F9) that is no version, a sign-in (F2) no laptop could complete, a telemetry relay (F8) with
// no collector to forward to. Each is a boot finding beside every other, naming its key and never a value an operator
// wrote in confidence.
package splice.core.topology

import splice.core.listen.IpLiterals
import splice.core.topology.metering.ServerMeteringChecks
import splice.core.util.LoopbackOverride
import splice.core.wire.HttpStatus
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

internal class ServerChecks(
    private val topology: Topology,
    /** splice.toml's directory, which a relative PEM path resolves against; null leaves the files unread. */
    private val configDir: Path?,
) {
    private val server = topology.server

    fun findings(): List<TopologyFinding> = if (server.declared) {
        listen() + exclusive() + commands() + tls() + people() + limits() + client() + signin() + telemetry() +
            ServerIdentityChecks(server).findings() + ServerLedgerChecks(server).findings() +
            ServerMeteringChecks(topology).findings()
    } else {
        emptyList()
    }

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

    /** Decisions 2 and 3: every command is one a head here runs, or a provider of splice.toml (the gateway serves it at
     *  the server's door with no head), on a provider that may serve a team. */
    private fun commands(): List<TopologyFinding> {
        val ofHeads = topology.heads.map { (key, head) -> head.claude.command ?: key }
        val offered = (ofHeads + topology.providers.keys).distinct()
        val has = offered.joinToString(", ").ifEmpty { "none" }
        val none = "list at least one command to serve; the commands here are $has".takeIf { server.commands.isEmpty() }
        val each = server.commands.mapNotNull { command ->
            val keys = topology.resolveHeadKeys(command)
            when {
                command == SERVER_TABLE ->
                    "no served command may be named '$SERVER_TABLE': that path on the listener is the server's own API"
                keys.isEmpty() && command in topology.providers -> personalPlan(command, topology.providers[command])
                keys.isEmpty() -> "'$command' is no command a head or provider here runs; the commands here are $has"
                keys.size > 1 -> "'$command' names more than one head (${keys.joinToString(", ")}); name one"
                else -> headCommand(command, keys.single())
            }
        }
        return (listOfNotNull(none) + each).map { TopologyFinding(SERVER_COMMANDS, it) }
    }

    /** Why [command], which head [headKey] runs, may not be served: it is also another provider's key, so a grant naming it
     *  would mean two providers (it keeps meaning the head's, and the other provider is reachable by no grant until one
     *  of them is renamed), or its provider signs with a personal plan. */
    private fun headCommand(command: String, headKey: String): String? {
        val head = topology.heads[headKey] ?: return null
        if (command in topology.providers && command != head.provider) {
            return "'$command' is the command of head $headKey, which runs ${head.provider}, and also the key of " +
                "another provider; rename one so that the name means one provider"
        }
        return personalPlan(command, topology.providers[head.provider])
    }

    /** Why [command], on [provider], may not be served to a team: its provider signs with a sign-in that is one
     *  person's plan. An API key or a self-hosted provider signs with nothing personal, and is served. */
    private fun personalPlan(command: String, provider: ProviderConfig?): String? {
        val kind = provider?.auth?.kind ?: return null
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
        val limits = server.limits
        val busy = setOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)
        return listOfNotNull(
            TopologyFinding("$SERVER_LIMITS.inflight_per_person", rule).takeIf { limits.inflightPerPerson < 0 },
            TopologyFinding("$SERVER_LIMITS.rpm_per_person", rule).takeIf { limits.rpmPerPerson < 0 },
            TopologyFinding("$SERVER_LIMITS.tpm_per_person", rule).takeIf { limits.tpmPerPerson < 0 },
            TopologyFinding("$SERVER_LIMITS.inflight_total", rule).takeIf { limits.inflightTotal < 0 },
            TopologyFinding("$SERVER_LIMITS.inflight_per_upstream", rule).takeIf { limits.inflightPerUpstream < 0 },
            TopologyFinding("$SERVER_LIMITS.busy_status", "must be ${busy.joinToString(" or ")}")
                .takeIf { limits.busyStatus !in busy },
            TopologyFinding("$SERVER_LIMITS.busy_retry_after", "must be 0 or a number of seconds")
                .takeIf { limits.busyRetryAfter < 0 },
            // The server's own waits, each a number of seconds where 0 turns it off.
            TopologyFinding("$SERVER_TABLE.keepalive_seconds", "must be 0 (no keepalive) or a number of seconds")
                .takeIf { server.keepaliveSeconds < 0 },
            TopologyFinding("$SERVER_TABLE.stream_idle_seconds", "must be 0 (no deadline) or a number of seconds")
                .takeIf { server.streamIdleSeconds < 0 },
            TopologyFinding(
                "$SERVER_TABLE.drain_seconds",
                "must be 0 (no drain) or a number of seconds up to $SERVER_DRAIN_MAX_SECONDS, which a stop waits out",
            ).takeIf { server.drainSeconds !in 0..SERVER_DRAIN_MAX_SECONDS },
        )
    }

    private fun client(): List<TopologyFinding> = listOfNotNull(
        TopologyFinding("$SERVER_TABLE.client.minimum", "must be a splice version, as in \"0.10.0\"")
            .takeIf { server.client.minimum != null && server.client.floor == null },
        TopologyFinding(
            "$SERVER_TABLE.client.download_url",
            "must be an http(s) URL, as in \"https://releases.team.example/splice\"",
        ).takeIf { server.client.downloadUrl?.let { url -> webUrl(url.trim()) == null } == true },
    )

    /** F8: a relay forwards to a collector, named by its http(s) URL. */
    private fun telemetry(): List<TopologyFinding> {
        val telemetry = server.telemetry ?: return emptyList()
        val why = "must be the operator's OTLP/HTTP collector, as in \"http://otel.lan:4318\""
        return listOfNotNull(
            TopologyFinding("$SERVER_TABLE.telemetry.forward_to", why).takeIf { webUrl(telemetry.collector) == null },
        )
    }

    /** F2: a sign-in a laptop can complete, against an issuer reached safely, admitting someone. */
    private fun signin(): List<TopologyFinding> {
        val signin = server.signin ?: return emptyList()
        val domainShape = Regex("""^@?[^@\s.]+(\.[^@\s.]+)+$""")
        return listOfNotNull(
            issuer(signin.issuer)?.let { TopologyFinding("$SERVER_SIGNIN.issuer", it) },
            TopologyFinding("$SERVER_SIGNIN.client_id", "must be the client id this server is registered as there")
                .takeIf { signin.clientId.isBlank() },
            TopologyFinding(
                "$SERVER_SIGNIN.client_secret_env",
                "must name an environment variable, as in \"SPLICE_SIGNIN_SECRET\"",
            ).takeIf { signin.clientSecretEnv?.isBlank() == true },
            TopologyFinding(
                "$SERVER_SIGNIN.domains",
                "list the email domains whose people may sign in, as in [\"team.example\"]",
            ).takeIf { signin.domains.isEmpty() || !signin.domains.all { domainShape.matches(it.trim()) } },
            TopologyFinding("$SERVER_SIGNIN.key_days", "must be a number of days, 1 or more")
                .takeIf { signin.keyDays < 1 },
            TopologyFinding("$SERVER_SIGNIN.rate_per_minute", "must be 0 (no cap) or more")
                .takeIf { signin.ratePerMinute < 0 },
        )
    }

    /** What is wrong with [named] as the issuer: no http(s) URL, or one that is not https off this machine. */
    private fun issuer(named: String): String? {
        val issuer = webUrl(named.trim())
        return when {
            issuer == null ->
                "must be the identity provider's issuer URL, as in \"https://accounts.google.com\""
            issuer.scheme == "http" && !LoopbackOverride.isLoopback(named) ->
                "must be https: the codes a laptop trades and the keys they mint would cross the network in the clear"
            else -> null
        }
    }

    /** [text] as a URI when it is an http(s) URL with a host, or null. */
    private fun webUrl(text: String): URI? {
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            null
        }
        return uri?.takeIf { it.host != null && it.scheme in setOf("http", "https") }
    }
}

private const val SERVER_TABLE = "server"

// why: a stop waits for the server's drain and then for the heads, and the daemon's whole cooperative stop is cut
// at 55 s, under the service manager's own bound; the heads' part of it is 50 s, which the drain spends first
// (Daemon.stop). A longer drain would be cut by the halt and promise turns a finish they never get.
private const val SERVER_DRAIN_MAX_SECONDS = 45
private const val SERVER_SIGNIN = "$SERVER_TABLE.signin"
private const val SERVER_LIMITS = "$SERVER_TABLE.limits"
private const val SERVER_COMMANDS = "$SERVER_TABLE.commands"
private const val SERVER_TLS_CERT = "$SERVER_TABLE.tls_cert"
private const val SERVER_TLS_KEY = "$SERVER_TABLE.tls_key"

// NEW: server mode (docs/specs/server-mode.md, F1, the limits half of F3, F9 and F12). The `[server]` table splice.toml
// carries on a machine that is a team's front door: where it listens, which commands it serves, the PEM files it speaks
// TLS from, the proxies whose word on a client's address it takes, who administers it, what each person may hold at
// once, how often a silent stream is kept alive (F4), the oldest splice a laptop may run (F9), the identity provider a
// person signs in with (F2), the collector Claude Code's own telemetry is relayed to (F8), the external signal that
// says whether the upstream is up, starting or off, and how pooled upstreams are probed for readiness. A file with no
// `[server]` decodes to the defaults, which serve nothing.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.topology.toml.TomlKeys
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/** `[server]`. [listen] is `host:port`, on any address; blank means the file has no server. */
@Serializable
public data class ServerConfig(
    val listen: String = "",
    /** The commands served to key holders, each by the name a head here runs as, or by the key of a provider (the
     *  gateway serves it at the server's door with no head). */
    val commands: List<String> = emptyList(),
    /** The certificate chain and its private key, as PEM files. Both, or neither when a proxy terminates TLS. */
    @SerialName("tls_cert") val tlsCert: String? = null,
    @SerialName("tls_key") val tlsKey: String? = null,
    /** The addresses and prefixes whose `X-Forwarded-For` names the client. From anyone else the header is ignored. */
    @SerialName("trusted_proxies") val trustedProxies: List<String> = emptyList(),
    /** The people whose keys open the server's own API on the listener, by email. */
    val admins: List<String> = emptyList(),
    val limits: ServerLimits = ServerLimits(),
    /** How often a streamed turn's client is sent an SSE keepalive while its answer is silent, as a model server
     *  prefilling a long prompt is; the stream is opened to the client at once for it. 0 sends none. */
    @SerialName("keepalive_seconds") val keepaliveSeconds: Int = 0,
    /** How long a streamed turn's answer may fall silent after its first event before it is ended with an error event
     *  (#791). 0 waits on it however long, which is the default: a model may think in silence, and only the operator
     *  knows how long theirs does. */
    @SerialName("stream_idle_seconds") val streamIdleSeconds: Int = 0,
    /** How long a stop waits for the turns in flight to finish, refusing new ones and answering `/health` as draining,
     *  before it closes the listener (#791). 0 gives them the listener's one second of grace, as before. */
    @SerialName("drain_seconds") val drainSeconds: Int = 0,
    /** What the same tokens would cost on a hosted model, beside each report's own cost (F5). */
    val reference: ServerReference = ServerReference(),
    /** Which team a person is on when the operator set none for them (decision 5). */
    val teams: ServerTeams = ServerTeams(),
    /** Where the ledger is copied, hour by hour (F5). */
    val export: ServerExport = ServerExport(),
    /** What each person may spend (F3). */
    val budgets: ServerBudgets = ServerBudgets(),
    /** The oldest splice a laptop may run against this server (F9). */
    val client: ServerClient = ServerClient(),
    /** `[server.signin]`: the identity provider a person signs in with to get a key; null when nobody signs in. */
    val signin: ServerSignin? = null,
    /** `[server.telemetry]`: the operator's collector, which each person's Claude Code telemetry is relayed to (F8);
     *  null relays none. */
    val telemetry: ServerTelemetry? = null,
    /** `[server.forward_auth]` (F13): dashboard SSO check (`GET /server/forward-auth`) admitting admins and emails
     *  matching [ServerForwardAuth.allow]; null disables the endpoint. */
    @SerialName("forward_auth") val forwardAuth: ServerForwardAuth? = null,
    /** `[server.backup]` (F14): durable state snapshots (`keys.json`, `people.json`, `budgets.json`, `ledger.jsonl`,
     *  `audit.jsonl`) written on a schedule and restored fail-closed on a fresh disk. */
    val backup: ServerBackup = ServerBackup(),
    /** `[server.aliases]` (#791): a first path segment, as `"/v1"`, mapped to a served command or `"door"`, so a client
     *  that cannot change its base path reaches `/<command>/v1/...` (or the team's door) at `/v1/...`. */
    val aliases: Map<String, String> = emptyMap(),
    /** The addresses and prefixes allowed to use the listener's admin privileges. Empty is unrestricted. */
    @SerialName("admin_cidrs") val adminCidrs: List<String> = emptyList(),
    /** The mark on newly minted keys. Stored keys keep working when it changes. */
    @SerialName("key_prefix") val keyPrefix: String = DEFAULT_SERVER_KEY_PREFIX,
    /** Literal prefix of every series on the server Prometheus scrape, including any separator. */
    @SerialName("metrics_prefix") val metricsPrefix: String = DEFAULT_SERVER_METRICS_PREFIX,
    /** External hourly meters by stable name. An empty table starts no probes. */
    @SerialName("cost_meters") val costMeters: Map<String, ServerCostMeter> = emptyMap(),
    /** `[server.state]`: the external signal that says whether the upstream is up, starting or off; null reads none. */
    val state: ServerState? = null,
    /** `[server.probe]`: how a pooled upstream earns its turns, and whether it holds any before its first probe. */
    val probe: ServerProbe = ServerProbe(),
) {
    /** Whether the file carries a `[server]` table at all, whatever it holds. */
    val declared: Boolean get() = this != ServerConfig()

    /** Where [listen] says to listen, or null when it is not an address and a port. */
    val address: ServerAddress? get() = ServerAddresses.read(listen)

    /** The PEM files as paths, a relative one under [configDir] (splice.toml's own directory); null without both. */
    public fun pem(configDir: Path): ServerPem? {
        val cert = tlsCert?.takeIf(String::isNotBlank) ?: return null
        val key = tlsKey?.takeIf(String::isNotBlank) ?: return null
        return ServerPem(configDir.resolve(cert), configDir.resolve(key))
    }

    /** [aliases] by the one path segment each answers, its `/` dropped, to the command or door it stands for. A key is
     *  written quoted, as `"/v1"`, and ktoml hands a quoted key back with its quotes. */
    public val aliasSegments: Map<String, String>
        get() {
            val keys = TomlKeys()
            return aliases.entries.associate { (prefix, target) ->
                keys.unquote(prefix).trim().removePrefix("/") to target.trim()
            }
        }

    /** Whether [person], a lowercased email, administers this server. */
    public fun admin(person: String): Boolean = admins.any { PersonEmails.read(it) == person }
}

/** `[server.limits]`: what each person may hold, unless `splice server person set` gave them their own. 0 is no cap. */
@Serializable
public data class ServerLimits(
    /** Turns one person may have in flight at once. */
    @SerialName("inflight_per_person") val inflightPerPerson: Int = 0,
    /** Turns one person may start in any minute. */
    @SerialName("rpm_per_person") val rpmPerPerson: Int = 0,
    /** Tokens, prompt and answer, of one person's turns that ended in the last minute, past which their next turn waits
     *  (F12). */
    @SerialName("tpm_per_person") val tpmPerPerson: Int = 0,
    /** Turns the whole server may have in flight at once, everyone's together (#791). */
    @SerialName("inflight_total") val inflightTotal: Int = 0,
    /** Turns each server of a provider that is several (`[[providers.<p>.upstreams]]`) may have in flight at once: a
     *  turn is refused while every healthy one holds this many (#791). */
    @SerialName("inflight_per_upstream") val inflightPerUpstream: Int = 0,
    /** The status a turn refused by [inflightTotal] or [inflightPerUpstream] is answered with: 429 (null, the default,
     *  which Claude Code and the OpenAI SDKs back off and retry on) or 503 (the server is full, which a load balancer
     *  reads as "try another"). */
    @SerialName("busy_status") val busyStatus: Int? = null,
    /** The seconds that refusal's `Retry-After` names. */
    @SerialName("busy_retry_after") val busyRetryAfter: Int = DEFAULT_BUSY_RETRY_AFTER,
    /** The sentence that refusal says in place of splice's own; blank says splice's. */
    @SerialName("busy_message") val busyMessage: String = "",
)

// why: a short turn on a small pool ends in seconds; five is long enough not to hammer a full server and short
// enough that a freed slot is taken soon after it frees.
private const val DEFAULT_BUSY_RETRY_AFTER = 5

/** `[server.telemetry]` (F8): [forwardTo] is the operator's OTLP/HTTP collector, as in "http://otel.lan:4318", which
 *  each signal is posted under (`/v1/logs`, `/v1/metrics`, `/v1/traces`) with the person who sent it on every resource. */
@Serializable
public data class ServerTelemetry(
    @SerialName("forward_to") val forwardTo: String = "",
    @SerialName("identity_attributes") val identityAttributes: OtlpIdentityAttributes = OtlpIdentityAttributes(),
) {
    /** [forwardTo] as the base each signal's path is put after, with no trailing slash. */
    public val collector: String get() = forwardTo.trim().trimEnd('/')
}

/** Trusted resource key names for the authenticated person's identity and team in relayed OTLP. */
@Serializable
public data class OtlpIdentityAttributes(
    val person: String = "splice.person",
    val email: String = "user.email",
    val team: String = "splice.team",
)

/** The literal every server Prometheus series starts with when `[server] metrics_prefix` is absent. */
public const val DEFAULT_SERVER_METRICS_PREFIX: String = "splice_server_"

/** `[server.cost_meters.<name>]`: one opt-in hourly meter. Exactly one source, [file], [url] or [argv], must be set.
 *  [hourlyUsd] reads as TOML dollars, so `hourly_usd = 3` is as good as `3.0`. */
@Serializable
public data class ServerCostMeter(
    @Serializable(with = TomlDollars::class) @SerialName("hourly_usd") val hourlyUsd: Double? = null,
    @SerialName("poll_seconds") val pollSeconds: Int = 60,
    val file: String? = null,
    val url: String? = null,
    val argv: List<String>? = null,
)

/** The listener's TLS material: the certificate chain and the private key, each a PEM file. */
public data class ServerPem(val cert: Path, val key: Path) {

    /** Whether [key] holds a private key in the one form the listener reads: unencrypted PKCS#8, which is what
     *  certbot writes. Some ACME clients write an EC key in its own older form, which [TLS_KEY_FORM] says how to
     *  convert. */
    internal fun keyIsPkcs8(): Boolean = String(Files.readAllBytes(key), Charsets.ISO_8859_1).contains(PKCS8_BEGIN)
}

// The line an unencrypted PKCS#8 private key's PEM block begins with.
private const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"

/** What a `tls_key` in any other form is told, at boot and by the listener. */
public const val TLS_KEY_FORM: String = "must be an unencrypted PKCS#8 key ($PKCS8_BEGIN); " +
    "`openssl pkcs8 -topk8 -nocrypt -in <key> -out <new key>` converts one"

/** Where the server listens: [host] as written (a name, an IPv4 address, or an IPv6 one without its brackets). */
public data class ServerAddress(val host: String, val port: Int)

/** Reads `[server] listen`: `0.0.0.0:3443`, `gateway.lan:443`, `[::]:3443`. */
internal object ServerAddresses {
    private val shape = Regex("""^(\[[0-9A-Fa-f:.]+]|[^\s:\[\]]+):(\d{1,5})$""")

    fun read(text: String): ServerAddress? {
        val found = shape.matchEntire(text.trim()) ?: return null
        val port = found.groupValues[2].toInt()
        return ServerAddress(found.groupValues[1].removeSurrounding("[", "]"), port)
    }
}

/** A person is a lowercased email (docs/specs/server-mode.md, decision 5). */
public object PersonEmails {
    private val shape = Regex("""^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$""")

    /** [text] as the person it names, trimmed and lowercased; null when it is not an email. */
    public fun read(text: String): String? = text.trim().lowercase().takeIf {
        shape.matches(it) && it.none(Char::isISOControl)
    }
}

/** Reads a web address an operator names in `[server]`: an http(s) URL with a host. */
internal object WebUrls {
    /** [text] as a URI when it is an http(s) URL with a host, or null. */
    fun read(text: String): URI? {
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            null
        }
        return uri?.takeIf { it.host != null && it.scheme in setOf("http", "https") }
    }
}

/** What a server port collision names as its owner in [Topology.portCollisions]: its own key path. */
internal const val SERVER_LISTEN_OWNER: String = "server.listen"

/** `[server.state]`: one external source the operator's own automation keeps current, saying whether the upstream
 *  behind this server is up, starting or off, read every [everySeconds]. Exactly one of [file], [url] and [command]
 *  names the source; a table naming none reads nothing and refuses boot, tuning keys alone included. [messageStarting]
 *  and [messageOff] are the words a refused turn carries (a source may bring its own); `{until}` in [messageOff] is
 *  where the time the upstream is off until goes. [retryAfterStarting] is the `Retry-After` a turn refused while the
 *  upstream is starting carries, and the one an off signal with no future `until` falls back to. */
@Serializable
public data class ServerState(
    /** A file holding the state, as plain text (`up`, `starting`, `off`, `off until <RFC3339>`) or JSON. */
    val file: String? = null,
    /** An http(s) URL a GET reads the state from, in the same plain-text or JSON form as [file]. */
    val url: String? = null,
    /** A command run as its argv stands, its standard output read as the state, in the same form as [file]. */
    val command: List<String>? = null,
    @SerialName("every_seconds") val everySeconds: Int = 5,
    @SerialName("message_starting") val messageStarting: String = "Upstream is starting; retry shortly.",
    @SerialName("message_off") val messageOff: String = "Upstream is off until {until}.",
    @SerialName("retry_after_starting") val retryAfterStarting: Int = 30,
)

/** `[server.probe]`: what a pooled upstream is asked before its health is believed. [kind] `health` asks its
 *  `/health` as before; `completion` asks it to really complete a minimal request for [model] (required then, and
 *  only for the chat-completions dialect pools allow today), which must answer within [timeoutSeconds].
 *  [everySeconds] is how often the completion probe runs; a health probe keeps its provider's `health_seconds`.
 *  [atStart] gates a completion probe's members at start: true (the default) holds each out until its first probe
 *  passes, false serves it from the start as a health probe always does. */
@Serializable
public data class ServerProbe(
    val kind: String = "health",
    val model: String? = null,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 30,
    @SerialName("every_seconds") val everySeconds: Int = 60,
    @SerialName("at_start") val atStart: Boolean = true,
)

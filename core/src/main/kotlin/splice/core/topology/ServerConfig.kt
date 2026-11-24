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
import splice.core.client.ClientVersion
import splice.core.client.ClientVersionParser
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
)

/** `[server.client]` (F9, F15): the oldest splice a laptop may run against this server, and optionally where laptops
 *  fetch the client build its policy wants. [minimum] is a dotted splice version, as in "0.10.0"; null lets every
 *  laptop in. [downloadUrl] is an http(s) base URL advertised in `GET /server/profile` and `GET /server/install`. */
@Serializable
public data class ServerClient(
    val minimum: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
) {
    /** [minimum] as a version compared number by number, or null when none is set or it is no version. */
    public val floor: ClientVersion? get() = ClientVersionParser().parse(minimum)
}

/** `[server.forward_auth]` (F13): who may open internal dashboards behind reverse-proxy `forward_auth`. Admins in
 *  `[server] admins` are always admitted; [allow] lists additional emails (`ada@team.example`) or `@domain` rules. */
@Serializable
public data class ServerForwardAuth(val allow: List<String> = emptyList()) {
    /** Whether [person] (lowercased email) is admitted by [allow] or by being an [isAdmin]. */
    public fun admits(person: String, isAdmin: Boolean): Boolean {
        if (isAdmin) return true
        val domain = person.substringAfterLast('@', "")
        return allow.any { raw ->
            val entry = raw.trim().lowercase()
            if (entry.startsWith("@")) {
                domain.isNotEmpty() && domain == entry.removePrefix("@")
            } else {
                PersonEmails.read(entry) == person
            }
        }
    }
}

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

/** `[server.signin]` (F2): the OpenID Connect provider a team member signs in with, the client this server is
 *  registered there as, the email domains it admits, how long a key minted by a sign-in lives, and how many sign-ins
 *  one address may try in a minute. */
@Serializable
public data class ServerSignin(
    /** The provider's issuer URL, which its `/.well-known/openid-configuration` is under and its ID tokens name. */
    val issuer: String = "",
    @SerialName("client_id") val clientId: String = "",
    /** The environment variable the client secret is read from; the secret itself is never in splice.toml. */
    @SerialName("client_secret_env") val clientSecretEnv: String? = null,
    /** The email domains whose people may sign in, as in `["team.example"]`. */
    val domains: List<String> = emptyList(),
    @SerialName("key_days") val keyDays: Int = DEFAULT_SIGNIN_KEY_DAYS,
    /** Sign-ins one client address may try in any minute; 0 is no cap. */
    @SerialName("rate_per_minute") val ratePerMinute: Int = DEFAULT_SIGNIN_RATE_PER_MINUTE,
) {
    /** Whether [email], a lowercased one, is in one of [domains]. */
    public fun admits(email: String): Boolean {
        val domain = email.substringAfterLast('@', "")
        return domain.isNotEmpty() && domains.any { it.trim().lowercase().removePrefix("@") == domain }
    }
}

// why: a month, as the spec's example sets it: a laptop that signs in at the start of each month never sees its key
// expire, and a person who left is out within the month even if nobody offboards them.
private const val DEFAULT_SIGNIN_KEY_DAYS = 30

// why: a person signs in a few times a day at most; twenty a minute from one address covers an office behind one NAT
// all signing in at nine, and holds a script trying codes to a trickle.
private const val DEFAULT_SIGNIN_RATE_PER_MINUTE = 20

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
    public fun read(text: String): String? = text.trim().lowercase().takeIf(shape::matches)
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

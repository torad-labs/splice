// NEW: server mode (docs/specs/server-mode.md, F1 and the limits half of F3). The `[server]` table splice.toml carries
// on a machine that is a team's front door: where it listens, which commands it serves, the PEM files it speaks TLS
// from, the proxies whose word on a client's address it takes, who administers it, what each person may hold at
// once, and how often a silent stream is kept alive (F4). A file with no `[server]` decodes to the defaults, which
// serve nothing.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/** `[server]`. [listen] is `host:port`, on any address; blank means the file has no server. */
@Serializable
public data class ServerConfig(
    val listen: String = "",
    /** The commands served to key holders, each by the name a head here runs as. */
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
    public fun read(text: String): String? = text.trim().lowercase().takeIf(shape::matches)
}

/** What a server port collision names as its owner in [Topology.portCollisions]: its own key path. */
internal const val SERVER_LISTEN_OWNER: String = "server.listen"

// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). The two tables splice.toml carries for it:
// `[share]` on a machine that offers its heads to the person's other machines on its LAN, and `[remote.<command>]` on
// a machine that runs one of them. A remote command holds the server's address and nothing else: no credential, no
// token and no copy of the server's models. What the address names (the head, which of the server's logins, which
// models) the server reads back from it and applies.
package splice.core.topology

import kotlinx.serialization.Serializable
import splice.core.util.Cancellables
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** `[share]`: whether this machine offers its heads on its LAN, on which port, and which further names a request may
 *  give this machine in its Host. Off unless the file turns it on. */
@Serializable
public data class ShareConfig(
    val enabled: Boolean = false,
    val port: Int = SHARE_DEFAULT_PORT,
    /** Names of this machine beyond its own host name, `.local`, `.lan`, `.home` and `.internal`: a name the router
     *  hands out under another domain, for one. */
    val hostnames: List<String> = emptyList(),
)

/** `[remote.<command>]`: a command this machine runs as Claude Code pointed at another machine's head, by [url], the
 *  address that machine's `splice share status` prints, with `auth=` and `models=` when the person chose them. */
@Serializable
public data class RemoteConfig(val url: String)

/** A remote command's address, read: the [server] (`http://torad-server.lan:3097`), the [head] it runs there, the
 *  server's login [auth] names, and the [models] the command is shown (empty: every model the head has). */
public data class RemoteAddress(val server: String, val head: String, val auth: String?, val models: List<String>) {
    /** Where the server takes this command's turns, as a base URL Claude Code can be given: Claude Code appends its own
     *  paths to it as text, so the login rides in the path (`/claudex/@work`), never in a query. */
    val turns: String get() = "$server/$head" + auth?.let { "/@$it" }.orEmpty()
}

/** What reading a remote command's address came to. */
public sealed class RemoteAddressRead {
    public data class Read(val address: RemoteAddress) : RemoteAddressRead()

    /** Not an address a remote command can run on: [why] says what is wrong with it, without repeating it. */
    public data class Refused(val why: String) : RemoteAddressRead()
}

/** Reads the addresses `[remote.<command>] url` holds: `http://<server>/<head>`, with `auth=<login>` and
 *  `models=<id>,<id>` as its only query. Anything else is refused with why, so a typo is said when the address is
 *  written, never found out as a launch that runs on the wrong login or shows the wrong models. */
public object RemoteAddresses {
    private val headName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    // The login label shape `splice login --label` accepts (AccountSelection).
    private val loginLabel = Regex("^[a-z0-9][a-z0-9._-]{0,47}$")
    private val modelId = Regex("^[^\\s,]+$")

    public fun read(text: String): RemoteAddressRead {
        // URI.create, not the constructor: its parse failure is an IllegalArgumentException, which is caught here.
        val uri = Cancellables.runCatchingCancellable { URI.create(text.trim()) }
            .getOrElse { return refused("it is not an address") }
        // A % that starts no escape (IllegalArgumentException, URLDecoder's own) is refused as that.
        val params = Cancellables.runCatchingCancellable { query(uri.rawQuery.orEmpty()) }
            .getOrElse { return refused("its query has a % that starts no escape") }
        val why = serverProblem(uri) ?: pathProblem(uri) ?: queryProblem(params)
        return if (why != null) refused(why) else RemoteAddressRead.Read(address(uri, params))
    }

    /** What is wrong with the server [uri] names, or null when nothing is. */
    private fun serverProblem(uri: URI): String? = when {
        uri.scheme?.lowercase() !in setOf("http", "https") -> "it must start with http:// or https://"
        uri.isOpaque || uri.host == null -> "it names no server"
        uri.rawUserInfo != null -> "it must not carry a user or a password"
        uri.port != -1 && uri.port !in MIN_PORT..MAX_PORT -> "its port is outside 1-65535"
        else -> null
    }

    /** What is wrong with what follows the server, before the query, or null when nothing is. */
    private fun pathProblem(uri: URI): String? = when {
        uri.rawFragment != null -> "it must not end in a #fragment"
        !headName.matches(head(uri)) -> "its path must be the one command the server offers, as in $EXAMPLE"
        else -> null
    }

    /** What is wrong with the query's [params], or null when nothing is. */
    private fun queryProblem(params: Map<String, List<String>>): String? {
        val repeated = params.filterValues { it.size > 1 }.keys.firstOrNull()
        val auth = params[AUTH]?.firstOrNull()
        val models = params[MODELS]?.firstOrNull()?.split(',')
        return when {
            (params.keys - setOf(AUTH, MODELS)).isNotEmpty() -> "only auth= and models= may follow the command"
            repeated != null -> "it names $repeated= more than once"
            auth != null && !loginLabel.matches(auth) -> "auth= must name one of the server's logins"
            models != null && !models.all(modelId::matches) -> "models= must list model ids, separated by commas"
            else -> null
        }
    }

    /** [uri] read, once neither check found a problem. */
    private fun address(uri: URI, params: Map<String, List<String>>): RemoteAddress {
        val models = params[MODELS]?.single()?.split(',')?.distinct().orEmpty()
        return RemoteAddress("${uri.scheme}://${uri.rawAuthority}", head(uri), params[AUTH]?.single(), models)
    }

    private fun head(uri: URI): String = uri.rawPath.orEmpty().removePrefix("/")

    /** Each query name with every value it was given, decoded. */
    private fun query(raw: String): Map<String, List<String>> = raw.split('&').filter { it.isNotEmpty() }
        .map { pair -> decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', "")) }
        .groupBy({ it.first }, { it.second })

    private fun decode(part: String): String = URLDecoder.decode(part, StandardCharsets.UTF_8)

    private fun refused(why: String): RemoteAddressRead = RemoteAddressRead.Refused(why)
}

private const val AUTH = "auth"
private const val MODELS = "models"
private const val EXAMPLE = "http://torad-server.lan:3097/claudex"
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

/** The port `[share]` listens on when the file names none. */
public const val SHARE_DEFAULT_PORT: Int = 3097

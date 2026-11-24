// NEW: server mode (docs/specs/server-mode.md, F7). `GET /server/profile`, for any live key: what this server offers
// its holder (ServerProfile), written from the config the server runs at each ask, so a command it stops serving
// leaves the profile with the restart that stops serving it. Each command is a head `[server] commands` names, with
// every model its launch offers and the window the head declares for each now. The sign-in is `[server.signin]`
// without its client secret, which only the server ever holds. The telemetry relay (F8), on a server with
// `[server.telemetry]`, is named at the address the laptop reached the server at, `<server>/server/otlp`, as a trusted
// proxy says it was reached when one is in front; a server with none says null. The minimum client is
// `[server.client] minimum` (F9), null when it takes any.
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import splice.core.server.SERVER_ROOT
import splice.core.topology.ServerConfig
import splice.core.wire.HTTPS_SCHEME
import splice.core.wire.HTTP_SCHEME
import splice.server.caller.ClientAddresses
import splice.server.telemetry.OTLP_ROUTE
import splice.share.PROFILE_PATH
import splice.share.ProfileClient
import splice.share.ProfileCommand
import splice.share.ProfileKey
import splice.share.ProfileModel
import splice.share.ProfileSignin
import splice.share.ProfileTelemetry
import splice.share.ServerProfile
import splice.share.SharedHeads
import java.time.Instant

internal class ProfileRoute(
    private val server: ServerConfig,
    /** The heads `[server] commands` names. */
    private val heads: SharedHeads,
    /** Blocking work runs here: reading each head's launch. */
    private val io: CoroutineDispatcher,
    /** Whose word on how a laptop reached the server is taken: the proxies `[server] trusted_proxies` names. */
    private val addresses: ClientAddresses,
    /** Whether the listener speaks SCHEME_HTTPS itself. */
    private val secure: Boolean,
) {
    private val json = Json { encodeDefaults = true }

    /** The path a laptop asks the profile at, after the server's address. */
    private val route = PROFILE_PATH.split('/')

    /** Whether [call], for [path], asks for the profile. */
    fun asks(call: ApplicationCall, path: List<String>): Boolean =
        path == route && call.request.httpMethod == HttpMethod.Get

    /** Answers [key]'s holder the profile. */
    suspend fun answer(call: ApplicationCall, key: ServerKey) {
        val commands = withContext(io) {
            heads.offered().map { head ->
                ProfileCommand(head.command, head.windows().map { (id, window) -> ProfileModel(id, window) })
            }
        }
        val profile = ServerProfile(
            key = ProfileKey(key.person, key.prefix, key.expiresAt?.let { Instant.ofEpochMilli(it).toString() }),
            commands = commands,
            signin = server.signin?.let { ProfileSignin(it.issuer, it.clientId, it.domains) },
            telemetry = server.telemetry?.let { ProfileTelemetry("${reachedAt(call)}/$SERVER_ROOT/$OTLP_ROUTE") },
            client = ProfileClient(minimum = server.client.minimum, downloadUrl = server.client.downloadUrl),
        )
        call.respondText(json.encodeToString(ServerProfile.serializer(), profile), ContentType.Application.Json)
    }

    /** The origin the laptop that sent [call] reached this server at, `scheme://host[:port]`. */
    private fun reachedAt(call: ApplicationCall): String {
        val peer = call.request.origin.remoteAddress
        val headers = call.request.headers
        val scheme = addresses.proxied(peer, headers[HttpHeaders.XForwardedProto])?.lowercase()
            ?.takeIf { it == HTTP_SCHEME || it == HTTPS_SCHEME }
            ?: if (secure) HTTPS_SCHEME else HTTP_SCHEME
        val host = (addresses.proxied(peer, headers[HttpHeaders.XForwardedHost]) ?: headers[HttpHeaders.Host])
            ?.takeIf(AUTHORITY::matches)
            ?: "${call.request.origin.localAddress}:${call.request.origin.localPort}"
        return "$scheme://$host"
    }
}

// A host and an optional port, as a Host header names them: a name, an IPv4 address or a bracketed IPv6 one.
private val AUTHORITY = Regex("""^(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(:\d{1,5})?$""")

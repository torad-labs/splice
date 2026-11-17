// NEW: server mode (docs/specs/server-mode.md, F7). `GET /server/profile`, for any live key: what this server offers
// its holder (ServerProfile), written from the config the server runs at each ask, so a command it stops serving
// leaves the profile with the restart that stops serving it. Each command is a head `[server] commands` names, with
// every model its launch offers and the window the head declares for each now. The sign-in is `[server.signin]`
// without its client secret, which only the server ever holds. This build relays no telemetry and keeps no client
// version policy (F8, F9), so the profile says so: telemetry null and no minimum.
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import splice.core.topology.ServerConfig
import splice.share.PROFILE_PATH
import splice.share.ProfileClient
import splice.share.ProfileCommand
import splice.share.ProfileKey
import splice.share.ProfileModel
import splice.share.ProfileSignin
import splice.share.ServerProfile
import splice.share.SharedHeads
import java.time.Instant

internal class ProfileRoute(
    private val server: ServerConfig,
    /** The heads `[server] commands` names. */
    private val heads: SharedHeads,
    /** Blocking work runs here: reading each head's launch. */
    private val io: CoroutineDispatcher,
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
            telemetry = null,
            client = ProfileClient(minimum = null),
        )
        call.respondText(json.encodeToString(ServerProfile.serializer(), profile), ContentType.Application.Json)
    }
}

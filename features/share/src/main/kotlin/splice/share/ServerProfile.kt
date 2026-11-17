// NEW: server mode (docs/specs/server-mode.md, F7). What a team's server offers a key holder, as its
// `GET /server/profile` answers it: whose key asked, each command it serves with its models and each model's context
// window, the sign-in configuration (never the client secret), the telemetry relay and the minimum client. The server
// writes it from the config it runs (server mode's ProfileRoute) and a laptop builds its commands from it:
// `splice login <server> --key-stdin` checks a key with it, and `splice remote add` and `refresh` link what it lists.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/** `GET /server/profile`, the answer. */
@Serializable
public data class ServerProfile(
    /** The key that asked: whose it is, never the key itself. */
    val key: ProfileKey,
    val commands: List<ProfileCommand>,
    /** How a person signs in to get a key of their own, or null on a server nobody signs in to. */
    val signin: ProfileSignin?,
    /** Where Claude Code's own telemetry is relayed, or null on a server that relays none. */
    val telemetry: ProfileTelemetry?,
    val client: ProfileClient,
)

/** Whose key asked: its [person], its visible [prefix], and when it expires, if it does. */
@Serializable
public data class ProfileKey(
    val person: String,
    val prefix: String,
    @SerialName("expires_at") val expiresAt: String?,
)

/** One command the server serves, by the name a laptop runs it as, with every model it serves. */
@Serializable
public data class ProfileCommand(val command: String, val models: List<ProfileModel>)

/** One model a command serves and the context window the server declares for it. */
@Serializable
public data class ProfileModel(val id: String, @SerialName("context_window") val contextWindow: Long)

/** The identity provider a person signs in with, the client the server is registered there as, and the email domains
 *  it admits. */
@Serializable
public data class ProfileSignin(
    val issuer: String,
    @SerialName("client_id") val clientId: String,
    val domains: List<String>,
)

/** The address Claude Code sends its OTLP to. */
@Serializable
public data class ProfileTelemetry(val otlp: String)

/** The oldest splice a laptop may run against the server, or null when it takes any. */
@Serializable
public data class ProfileClient(val minimum: String?)

/** A laptop's ask of a server's profile, with the key it holds or one it was handed. */
public class ServerProfiles(
    io: CoroutineDispatcher,
    /** The keys this laptop holds, each kept by `splice login <server>`. */
    private val logins: ServerLogins? = null,
) {
    private val http = ShareHttp(io)

    /** What [server] offers this laptop, asked with the key it holds there; null when it holds none, as on a LAN
     *  share, which has no profile. */
    public suspend fun held(server: String): Asked<ServerProfile>? = logins?.of(server)?.let { read(server, it) }

    /** What [server] (an origin) offers the holder of [key], or why it could not be had: a key it refuses is said in
     *  the server's own words. */
    public suspend fun read(server: String, key: String): Asked<ServerProfile> = http.read(
        server,
        http.get(URI.create("$server/$PROFILE_PATH"), key),
        not = "not with a splice server's profile",
    ) { http.decoded(it, ServerProfile.serializer()) }
}

/** Where a server answers its profile, under its origin. */
public const val PROFILE_PATH: String = "server/profile"

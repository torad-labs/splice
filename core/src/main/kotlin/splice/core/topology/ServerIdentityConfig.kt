package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.client.ClientVersion
import splice.core.client.ClientVersionParser
import splice.core.client.OUTDATED_IN_CONVERSATION
import splice.core.client.SPLICE_CLIENT_HEADER

/** `[server.client]` (F9, F15): the oldest splice a laptop may run against this server, and optionally where laptops
 *  fetch the client build its policy wants. [minimum] is a dotted splice version, as in "0.10.0"; null lets every
 *  laptop in. [downloadUrl] is an http(s) base URL advertised in `GET /server/profile` and `GET /server/install`. */
@Serializable
public data class ServerClient(
    val minimum: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    /** #791: the request header a laptop's launcher names its version in; blank reads `x-splice-client`. */
    @SerialName("version_header") val versionHeader: String = "",
    /** #791: what an older laptop's turn is answered with: 200 says the notice in the conversation, 400 or 426
     *  (Upgrade Required) refuses the turn with it. */
    @SerialName("outdated_status") val outdatedStatus: Int = OUTDATED_IN_CONVERSATION,
    /** #791: what an older laptop is told, `{version}` and `{minimum}` filled in; blank says splice's own words. */
    @SerialName("outdated_message") val outdatedMessage: String = "",
    /** #791: whether a laptop may report its version once with `POST /server/client/checkin`, for a launcher that
     *  sends no version on each turn. */
    val checkin: Boolean = false,
) {
    /** [minimum] as a version compared number by number, or null when none is set or it is no version. */
    public val floor: ClientVersion? get() = ClientVersionParser().parse(minimum)

    /** The header a laptop's version is read from. */
    public val header: String get() = versionHeader.trim().ifEmpty { SPLICE_CLIENT_HEADER }
}

/** `[server.forward_auth]`: dashboard access for admins and the emails or `@domain` rules in [allow]. */
@Serializable
public data class ServerForwardAuth(
    val allow: List<String> = emptyList(),
    /** HTTPS origin serving both the protected app and the browser sign-in routes. Null keeps key-only mode. */
    @SerialName("browser_origin") val browserOrigin: String? = null,
    @SerialName("session_seconds") val sessionSeconds: Int = DEFAULT_BROWSER_SESSION_SECONDS,
    @SerialName("email_header") val emailHeader: String = "X-Auth-Request-Email",
    @SerialName("role_header") val roleHeader: String? = null,
    /** Optional separate OAuth registration for the browser callback, at the same issuer. */
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("client_secret_env") val clientSecretEnv: String? = null,
) {
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
    /** When set, the signed ID token must also carry this exact hosted domain in its `hd` claim. */
    @SerialName("hosted_domain") val hostedDomain: String? = null,
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

// why: an hour keeps a dashboard tab working through a working session, and an offboarded person's browser is out
// within the hour even if nobody revokes its session.
private const val DEFAULT_BROWSER_SESSION_SECONDS = 3_600

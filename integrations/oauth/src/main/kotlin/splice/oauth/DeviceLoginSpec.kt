// NEW: everything the device-authorization login needs for one provider.
// Split from DeviceLoginFlow.kt so the poller is not billed for the spec
// (concentration, 2026-08-19). Same-package FQCN is unchanged.
package splice.oauth

import java.nio.file.Path

/** Runs after a successful credential write; the default is a no-op so kimi stays unchanged. */
public fun interface DeviceLoginFinalizer {
    public suspend operator fun invoke(authPath: Path, account: OAuthLoginAccount?)
}

/** RFC 8628 device-authorization request body (`client_id=...`). */
public fun interface DeviceAuthForm {
    public operator fun invoke(clientId: String): String
}

/** RFC 8628 device-authorization response -> the fields the poller announces and waits on. */
public fun interface DeviceAuthParse {
    public operator fun invoke(body: String): DeviceAuthorization
}

/** RFC 8628 token-poll request body (`client_id` + `device_code` + grant_type). */
public fun interface TokenPollForm {
    public operator fun invoke(deviceCode: String, clientId: String): String
}

/** Parsed device_authorization response. Vendor defaults for omitted expires/interval live in the parse. */
public data class DeviceAuthorization(
    public val userCode: String,
    public val deviceCode: String,
    public val verificationUri: String,
    public val verificationUriComplete: String,
    public val expiresInS: Long,
    public val intervalS: Long,
)

/** Who the poller says it is on both OAuth calls: the client id and any extra identity headers. */
public data class DeviceClient(
    public val id: String,
    /** Extra identity headers on both OAuth calls; LoginIo already sends Accept application/json. */
    public val headers: Map<String, String>,
)

/** The device_authorization call: where it posts, the body it sends, and how its answer is read. */
public data class DeviceAuthEndpoint(
    public val url: String,
    public val form: DeviceAuthForm,
    public val parse: DeviceAuthParse,
)

/** The token endpoint the poller waits on: where it posts, the poll body, and what a success persists. */
public data class DeviceTokenEndpoint(
    public val url: String,
    public val pollForm: TokenPollForm,
    /** token-endpoint success body → the auth.json content to persist. */
    public val toAuthJson: AuthJsonFromResponse,
)

/** Everything the device flow needs for one provider's login (built by LoginCommand per head). */
public data class DeviceLoginSpec(
    public val head: String,
    public val client: DeviceClient,
    public val deviceAuth: DeviceAuthEndpoint,
    public val tokenEndpoint: DeviceTokenEndpoint,
    public val authPath: Path,
    public val account: OAuthLoginAccount? = null,
    public val afterPersist: DeviceLoginFinalizer = DeviceLoginFinalizer { _, _ -> },
)

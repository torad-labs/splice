// NEW: a native command alone owns its rotating refresh token; splice reads only a live access token.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.REFUSAL_FIELD
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaHeaderRead
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialEvidence
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
private data class NativeAccessDocument(val claudeAiOauth: NativeAccessToken? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
private data class NativeAccessToken(val accessToken: String? = null, val expiresAt: Long? = null) {
    override fun toString(): String = "NativeAccessToken(<redacted>)"
}

internal class ClaudeNativeAuth(
    private val directory: Path,
    private val place: ClaudeLoginPlaceId,
    private val profiles: ClaudeCredentialProfiles,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : RefreshableAuthProvider, AccountCredentialIdentitySource {
    private val family = ClientAuthProvider(place.command)

    val credentialKey: String?
        get() = observe()?.accessToken?.let {
            CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $it"))
        }

    override suspend fun credentials(): Credentials? {
        val observed = observe()
        return observed?.takeIf(::usable)?.accessToken?.let(Credentials::Bearer)
    }

    /** The native Claude Code command is the only refresher and credential writer. */
    override suspend fun refresh(): Credentials? = null

    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean = false

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialEvidence(): CredentialEvidence =
        if (observe()?.let(::usable) == true) {
            CredentialFileEvidenceReader.read(directory.resolve(CREDENTIALS_JSON))
        } else {
            CredentialEvidence(null, CredentialPresence.MISSING)
        }

    override suspend fun describe(): AuthDescription {
        val observed = observe()
        val present = observed?.let(::usable) == true
        val fields = mutableMapOf(
            "auth_path" to directory.resolve(CREDENTIALS_JSON).toString(),
            "native_place" to place.wire,
        )
        credentialKey?.let(profiles::read)?.let { account ->
            fields["account_uuid"] = account.uuid
            account.email?.let { fields["account_email"] = it }
        }
        if (!present) {
            fields[REFUSAL_FIELD] = if (observed?.expiresAt?.let { it <= clock() } == true) {
                "native access token expired; run ${place.command} to refresh its own login"
            } else {
                "native login has no usable access token and expiry; run ${place.command} to sign in"
            }
        }
        return AuthDescription(present, CLAUDE_ACCOUNT_AUTH_KIND, fields)
    }

    override fun planLimit(header: QuotaHeaderRead, nowEpochSeconds: Long): PlanLimit? =
        family.planLimit(header, nowEpochSeconds)

    private fun usable(token: NativeAccessToken): Boolean =
        !token.accessToken.isNullOrBlank() && token.expiresAt?.let { it > clock() } == true

    private fun observe(): NativeAccessToken? = read()

    private fun read(): NativeAccessToken? = Cancellables.runCatchingCancellable {
        Json.decodeFromString<NativeAccessDocument>(Files.readString(directory.resolve(CREDENTIALS_JSON))).claudeAiOauth
    }.fold(
        onSuccess = { it },
        onFailure = {
            if (it !is java.nio.file.NoSuchFileException) {
                log("[native-login] credential unreadable (${it::class.simpleName})\n")
            }
            null
        },
    )
}

// NEW: a native command alone owns its rotating refresh token; splice reads only a live access token.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.TOKEN_RENEWS_FIELD
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.REFUSAL_FIELD
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialEvidence
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
private data class NativeAccessDocument(val claudeAiOauth: NativeAccessToken? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
private data class NativeAccessToken(
    val accessToken: String? = null,
    val expiresAt: Long? = null,
    val refreshToken: String? = null,
) {
    override fun toString(): String = "NativeAccessToken(<redacted>)"
}

internal class ClaudeNativeAuth(
    private val directory: Path,
    private val place: ClaudeLoginPlaceId,
    private val profiles: ClaudeCredentialProfiles,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : RefreshableAuthProvider, AccountCredentialIdentitySource {
    val credentialKey: String?
        get() = observe()?.accessToken?.let {
            CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $it"))
        }

    override fun observedCredentialKey(): String? = credentialKey

    /** The account profile read for this token, so a refreshed token reads as the same login (Oct 19). */
    override fun credentialAccount(): String? = credentialKey?.let(profiles::read)?.uuid

    override suspend fun credentials(): Credentials? {
        val observed = observe()
        return observed?.takeIf(::usable)?.accessToken?.let(Credentials::Bearer)
    }

    /** The native Claude Code command is the only refresher and credential writer. */
    override suspend fun refresh(): Credentials? = null

    override fun allowRefreshAfterFailure(deadCredential: Boolean): Boolean = false

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialEvidence(): CredentialEvidence {
        val observed = observe()
        return if (observed?.let(::usable) == true) {
            CredentialFileEvidenceReader.read(directory.resolve(CREDENTIALS_JSON))
        } else {
            CredentialEvidence(null, CredentialPresence.MISSING, renewable = renews(observed))
        }
    }

    override suspend fun describe(): AuthDescription {
        val observed = observe()
        val present = observed?.let(::usable) == true
        val fields = mutableMapOf(
            "auth_path" to directory.resolve(CREDENTIALS_JSON).toString(),
            "native_place" to place.wire,
        )
        credentialKey?.let(profiles::read)?.let { account ->
            fields[ACCOUNT_REF_FIELD] = AccountRef.of(account.uuid)
        }
        if (!present) fields[REFUSAL_FIELD] = refusal(observed)
        if (!present && renews(observed)) fields[TOKEN_RENEWS_FIELD] = "true"
        return AuthDescription(present, CLAUDE_ACCOUNT_AUTH_KIND, fields)
    }

    /** Why the place cannot send. An expired token beside its refresh token needs only its command run: Claude Code
     *  renews it there, and splice never does (it owns no rotating refresh token). Only a place with nothing to renew
     *  from needs a new sign-in. */
    private fun refusal(observed: NativeAccessToken?): String = when {
        renews(observed) -> "Access token expired. Run ${place.command} once and Claude Code renews it."
        observed?.expiresAt?.let { it <= clock() } == true ->
            "Access token expired. Sign in again: run ${place.command} and use /login."
        else -> "No usable access token. Sign in again: run ${place.command} and use /login."
    }

    private fun renews(observed: NativeAccessToken?): Boolean =
        observed?.expiresAt?.let { it <= clock() } == true && !observed.refreshToken.isNullOrBlank()

    private fun usable(token: NativeAccessToken): Boolean =
        !token.accessToken.isNullOrBlank() && token.expiresAt?.let { it > clock() } == true

    private val file = NativeCredentialFile(directory.resolve(CREDENTIALS_JSON), log)

    private fun observe(): NativeAccessToken? = file.read()
}

/** The native login's credential file, parsed again only when the file moved. */
private class NativeCredentialFile(private val path: Path, private val log: LogSink) {
    // why: read() runs on every observation (several per turn, and on each membership refresh), so a file that stays
    // unreadable is named when its failure class changes, not on every read.
    @Volatile
    private var lastClass: String? = null

    // why: the same observation runs for every pool pick, quota snapshot and evidence refresh, so the file is parsed again
    // only when its identity, size or modification time moved (JFR Oct 20, 2026: 456 of 13,317 allocation samples here).
    @Volatile
    private var held: Pair<List<Any?>, NativeAccessToken?>? = null

    fun read(): NativeAccessToken? {
        val stamp = stampOf()
        held?.takeIf { stamp != null && it.first == stamp }?.let { return it.second }
        val token = parse()
        held = if (stamp != null && lastClass == null) stamp to token else null
        return token
    }

    /** The file's identity, size and modification time, or null when it cannot be read, so nothing is reused. */
    private fun stampOf(): List<Any?>? = try {
        val attributes = Files.readAttributes(path, "fileKey,size,lastModifiedTime")
        listOf(attributes["fileKey"], attributes["size"], attributes["lastModifiedTime"])
    } catch (_: IOException) {
        // The parse that follows names the failure; an unstamped file is simply read again.
        null
    }

    private fun parse(): NativeAccessToken? = Cancellables.runCatchingCancellable {
        Json.decodeFromString<NativeAccessDocument>(Files.readString(path)).claudeAiOauth
    }.fold(
        onSuccess = {
            lastClass = null
            it
        },
        onFailure = {
            val kind = if (it is java.nio.file.NoSuchFileException) null else it::class.simpleName
            if (kind != null && kind != lastClass) log("[native-login] credential unreadable ($kind)\n")
            lastClass = kind
            null
        },
    )
}

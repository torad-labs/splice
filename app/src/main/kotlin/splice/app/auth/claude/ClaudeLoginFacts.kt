// NEW: native identity and the private standing join are read from the files each command actually uses.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.decodeFromStream
import splice.accounts.claude.ClaudeAccountIdentity
import splice.core.auth.CredentialKey
import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

@Serializable
@JsonIgnoreUnknownKeys
private data class NativeCredentialDocument(val claudeAiOauth: NativeToken? = null)

@Serializable
@JsonIgnoreUnknownKeys
private data class NativeToken(val accessToken: String? = null)

/** The digest is an internal join only. The account route receives identity and windows, never this key. */
internal data class ClaudeLoginFacts(
    val present: Boolean,
    val account: ClaudeAccountIdentity?,
    val key: String?,
    val refusal: String? = null,
)

/** Streaming typed reads skip unrelated settings and project histories instead of retaining their JSON trees. */
internal class ClaudeLoginFactsReader(
    private val profiles: ClaudeCredentialProfiles? = null,
    private val profileRefresh: ClaudeIdentityRefresh? = null,
) {
    private val json = Json

    fun read(location: ClaudeLoginLocation): ClaudeLoginFacts {
        val failures = mutableListOf<Throwable>()
        val credential = Cancellables.runCatchingCancellable {
            Files.newInputStream(location.credentials).use { json.decodeFromStream<NativeCredentialDocument>(it) }
        }.onFailure { failures += it }.getOrNull()
        val token = credential?.claudeAiOauth?.accessToken?.takeIf { it.isNotBlank() }
        val identity = token?.let(::identified)
        val failure = failures.firstOrNull { it !is NoSuchFileException }
        return ClaudeLoginFacts(
            present = credential != null || Files.exists(location.credentials, java.nio.file.LinkOption.NOFOLLOW_LINKS),
            account = identity,
            key = token?.let { CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $it")) },
            refusal = failure?.let { "native Claude login unreadable (${it::class.simpleName})" },
        )
    }

    /** Only a profile verified for this folder's actual token proves its account. Copied settings prove nothing. */
    fun identity(configDir: Path): ClaudeAccountIdentity? = token(configDir)?.let(::identified)

    private fun identified(token: String): ClaudeAccountIdentity? {
        val key = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")) ?: return null
        val account = profiles?.read(key)
        if (account == null) profileRefresh?.request(key, token)
        return account
    }

    /** An explicit product refresh awaits the same captured credential's profile, never a later file's token. */
    suspend fun refresh(configDir: Path) {
        val token = token(configDir) ?: return
        val key = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")) ?: return
        profileRefresh?.request(key, token)?.await()
    }

    /** The access token a CONFIG DIR's credential file holds, or null when it holds none that splice can read.
     *  Read at send and probe time only; the value never enters a log, a view or another type. */
    fun token(configDir: Path): String? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- as identity(), and no fact about a token may reach a log
        Cancellables.runCatchingCancellable {
            Files.newInputStream(configDir.resolve(CREDENTIALS_JSON)).use {
                json.decodeFromStream<NativeCredentialDocument>(it)
            }.claudeAiOauth?.accessToken?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** A stored display alias is metadata only; neither credential files nor stable pool ids are renamed. */
    fun displayName(record: Path): String? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- absent or unreadable alias uses the login's own stable id
        Cancellables.runCatchingCancellable {
            Files.newInputStream(record).use { json.decodeFromStream<ClaudeAccountRecord>(it) }.displayName
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    /** When splice filed the folder [record] sits in, or null when there is no readable record: a folder from before
     *  the record existed, which sorts first for exactly that reason. */
    fun addedAt(record: Path): Long? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no record means filed before the record; it sorts first
        Cancellables.runCatchingCancellable {
            Files.newInputStream(record).use { json.decodeFromStream<ClaudeAccountRecord>(it) }.addedAtEpochMillis
        }.getOrNull()
}

// why: the two file names Claude Code itself reads in a config dir. internal, not private: this package's folder
// store and its auth provider read the same two names, and the const law wants one declaration for them.
internal const val CREDENTIALS_JSON = ".credentials.json"
internal const val CLAUDE_JSON = ".claude.json"

/** splice's own record in a per-account folder: when the account was added, which is the pool's default order.
 *  Claude Code neither writes nor reads it, so it carries nothing of the account itself. */
@Serializable
internal data class ClaudeAccountRecord(
    @SerialName(ADDED_AT) val addedAtEpochMillis: Long,
    @SerialName("display_name") val displayName: String? = null,
) {
    fun wire(): String = Json.encodeToString(serializer(), this)
}

// why: the field name in the file, snake_case like the Claude Code documents it sits beside.
private const val ADDED_AT = "added_at"

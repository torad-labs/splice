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

@Serializable
@JsonIgnoreUnknownKeys
private data class NativeAccountDocument(val oauthAccount: NativeAccount? = null)

@Serializable
@JsonIgnoreUnknownKeys
private data class NativeAccount(val accountUuid: String? = null, val emailAddress: String? = null)

/** The digest is an internal join only. The account route receives identity and windows, never this key. */
internal data class ClaudeLoginFacts(
    val present: Boolean,
    val account: ClaudeAccountIdentity?,
    val key: String?,
    val refusal: String? = null,
)

/** Streaming typed reads skip unrelated settings and project histories instead of retaining their JSON trees. */
internal class ClaudeLoginFactsReader {
    private val json = Json

    fun read(location: ClaudeLoginLocation): ClaudeLoginFacts {
        val failures = mutableListOf<Throwable>()
        val credential = Cancellables.runCatchingCancellable {
            Files.newInputStream(location.credentials).use { json.decodeFromStream<NativeCredentialDocument>(it) }
        }.onFailure { failures += it }.getOrNull()
        val identity = Cancellables.runCatchingCancellable { account(location.target.accountFile) }
            .onFailure { failures += it }.getOrNull()
        val token = credential?.claudeAiOauth?.accessToken?.takeIf { it.isNotBlank() }
        val failure = failures.firstOrNull { it !is NoSuchFileException }
        return ClaudeLoginFacts(
            present = credential != null || Files.exists(location.credentials, java.nio.file.LinkOption.NOFOLLOW_LINKS),
            account = identity,
            key = token?.let { CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $it")) },
            refusal = failure?.let { "native Claude login unreadable (${it::class.simpleName})" },
        )
    }

    /** The account a CONFIG DIR records, or null when it holds none that splice can read. The folder form of
     *  [read]'s identity half, for the per-account folders a Claude head's pool is built from. */
    fun identity(configDir: Path): ClaudeAccountIdentity? =
        Cancellables.runCatchingCancellable { account(configDir.resolve(CLAUDE_JSON)) }.getOrNull()

    /** The access token a CONFIG DIR's credential file holds, or null when it holds none that splice can read.
     *  Read at send and probe time only; the value never enters a log, a view or another type. */
    fun token(configDir: Path): String? = Cancellables.runCatchingCancellable {
        Files.newInputStream(configDir.resolve(CREDENTIALS_JSON)).use {
            json.decodeFromStream<NativeCredentialDocument>(it)
        }.claudeAiOauth?.accessToken?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** When splice filed the folder [record] sits in, or null when there is no readable record. */
    fun addedAt(record: Path): Long? = Cancellables.runCatchingCancellable {
        Files.newInputStream(record).use { json.decodeFromStream<ClaudeAccountRecord>(it) }.addedAtEpochMillis
    }.getOrNull()

    private fun account(file: Path): ClaudeAccountIdentity? {
        val record = Files.newInputStream(file).use { json.decodeFromStream<NativeAccountDocument>(it) }.oauthAccount
        val uuid = record?.accountUuid?.takeIf { it.isNotBlank() } ?: return null
        return ClaudeAccountIdentity(uuid, record.emailAddress)
    }
}

// why: the two file names Claude Code itself reads in a config dir. internal, not private: this package's folder
// store and its auth provider read the same two names, and the const law wants one declaration for them.
internal const val CREDENTIALS_JSON = ".credentials.json"
internal const val CLAUDE_JSON = ".claude.json"

/** splice's own record in a per-account folder: when the account was added, which is the pool's default order.
 *  Claude Code neither writes nor reads it, so it carries nothing of the account itself. */
@Serializable
internal data class ClaudeAccountRecord(@SerialName(ADDED_AT) val addedAtEpochMillis: Long) {
    fun wire(): String = """{"$ADDED_AT":$addedAtEpochMillis}"""
}

// why: the field name in the file, snake_case like the Claude Code documents it sits beside.
private const val ADDED_AT = "added_at"

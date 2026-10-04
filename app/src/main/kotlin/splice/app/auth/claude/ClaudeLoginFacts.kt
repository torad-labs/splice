// NEW: native identity and the private standing join are read from the files each command actually uses.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

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

    private fun account(file: Path): ClaudeAccountIdentity? {
        val record = Files.newInputStream(file).use { json.decodeFromStream<NativeAccountDocument>(it) }.oauthAccount
        val uuid = record?.accountUuid?.takeIf { it.isNotBlank() } ?: return null
        return ClaudeAccountIdentity(uuid, record.emailAddress)
    }
}

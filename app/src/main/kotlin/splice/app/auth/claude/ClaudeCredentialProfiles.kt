// NEW: a Claude account is proved by the credential's provider profile, never by copied client settings.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import splice.accounts.claude.ClaudeAccountIdentity
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.oauth.AuthHttpClientFactory
import splice.upstream.codemode.ProcessElapsedNow
import splice.usage.quota.ClientUserAgent
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

// why: Claude Code resolves the account authenticated by an OAuth token at this endpoint.
private const val CLAUDE_PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"

// why: the existing credential join is a SHA-256 digest rendered as lower-case hexadecimal.
private const val PROFILE_CREDENTIAL_KEY_CHARS = 64

// why: active failure lookups retain only a bounded hot set; durable empty identities cover evicted old grants.
private const val PROFILE_FAILED_KEYS = 256

// why: transient profile failures are suppressed across page reads, but may retry after fifteen minutes.
private val PROFILE_TRANSIENT_BACKOFF_MS = 15.minutes.inWholeMilliseconds

@Serializable
@JsonIgnoreUnknownKeys
private data class ClaudeProfileDocument(val account: ClaudeProfileAccount? = null)

@Serializable
@JsonIgnoreUnknownKeys
private data class ClaudeProfileAccount(val uuid: String? = null, val email: String? = null)

/** Only UUID and displayed email are persisted. The filename is the existing private credential digest. */
internal class ClaudeCredentialProfiles(private val stateDir: Path, private val log: LogSink) {
    private val json = Json

    fun read(key: String): ClaudeAccountIdentity? = try {
        val account = json.decodeFromString<ClaudeProfileAccount>(Files.readString(file(key)))
        account.uuid?.takeIf(String::isNotBlank)?.let { ClaudeAccountIdentity(it, account.email) }
    } catch (_: NoSuchFileException) {
        null
    } catch (failure: java.io.IOException) {
        log("[claude-profile] verified identity unreadable (${failure::class.simpleName})\n")
        null
    } catch (failure: kotlinx.serialization.SerializationException) {
        log("[claude-profile] verified identity malformed (${failure::class.simpleName})\n")
        null
    }

    fun observed(key: String, account: ClaudeAccountIdentity) {
        require(account.uuid.isNotBlank()) { "the provider profile named no account" }
        write(key, ClaudeProfileAccount(account.uuid, account.email))
    }

    /** An empty identity remembers a failed key across restarts, without storing response or failure text. */
    fun failed(key: String) {
        if (!attempted(key)) write(key, ClaudeProfileAccount(null))
    }

    fun attempted(key: String): Boolean = Files.exists(file(key))

    private fun write(key: String, record: ClaudeProfileAccount) {
        Cancellables.runCatchingCancellable {
            SecureFile.writeAtomic0600(file(key), json.encodeToString(ClaudeProfileAccount.serializer(), record))
        }.onFailure { log("[claude-profile] verified identity write failed (${it::class.simpleName})\n") }
    }

    private fun file(key: String): Path {
        require(key.length == PROFILE_CREDENTIAL_KEY_CHARS && key.all { it in '0'..'9' || it in 'a'..'f' }) {
            "invalid credential identity"
        }
        return stateDir.resolve("claude-credential-identities").resolve("$key.json")
    }
}

/** Failure classification is provider evidence, never a copied settings identity. */
internal sealed class ClaudeProfileResult {
    data class Verified(val account: ClaudeAccountIdentity) : ClaudeProfileResult()
    data object Refused : ClaudeProfileResult()
    data object Transient : ClaudeProfileResult()
}

/** Product-only profile read. Its result contains no credential or unused profile field. */
internal fun interface ClaudeProfileCall {
    suspend fun read(token: String): ClaudeAccountIdentity?

    suspend fun result(token: String): ClaudeProfileResult =
        read(token)?.let(ClaudeProfileResult::Verified) ?: ClaudeProfileResult.Refused
}

internal class ClaudeProfileProbe(
    private val userAgent: ClientUserAgent,
    private val log: LogSink,
    private val client: AuthClient = AuthClient(AuthHttpClientFactory()::create),
) : ClaudeProfileCall {
    private val json = Json

    override suspend fun read(token: String): ClaudeAccountIdentity? =
        (result(token) as? ClaudeProfileResult.Verified)?.account

    override suspend fun result(token: String): ClaudeProfileResult =
        Cancellables.runCatchingCancellable { fetch(token) }.getOrElse { failure ->
            log("[claude-profile] profile read failed (${failure::class.simpleName})\n")
            ClaudeProfileResult.Transient
        }

    private suspend fun fetch(token: String): ClaudeProfileResult = client.open().use { http ->
        val response = http.get(CLAUDE_PROFILE_URL) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
            userAgent.latest()?.let { header("User-Agent", it) }
        }
        if (response.status != HttpStatusCode.OK) {
            log("[claude-profile] profile refused with ${response.status.value}\n")
            return@use when (response.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> ClaudeProfileResult.Refused
                else -> ClaudeProfileResult.Transient
            }
        }
        val account = json.decodeFromString<ClaudeProfileDocument>(response.bodyAsText()).account
        account?.uuid?.takeIf(String::isNotBlank)?.let {
            ClaudeProfileResult.Verified(ClaudeAccountIdentity(it, account.email))
        } ?: ClaudeProfileResult.Refused
    }
}

/** Borrows the daemon's scope. One in-flight read per captured credential, with no token persisted or logged. */
internal class ClaudeIdentityRefresh(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val profiles: ClaudeCredentialProfiles,
    private val probe: ClaudeProfileCall,
    private val log: LogSink,
    private val clock: ElapsedClock = ProcessElapsedNow(),
) {
    private val active = ConcurrentHashMap<String, Deferred<ClaudeAccountIdentity?>>()
    private val failures = LinkedHashMap<String, Long?>()

    private fun remembered(key: String): Boolean {
        val held = synchronized(failures) {
            if (key !in failures) {
                false
            } else {
                val until = failures[key]
                if (until == null || until > clock()) true else false.also { failures.remove(key) }
            }
        }
        return held || profiles.attempted(key)
    }

    private fun rememberFailure(key: String, until: Long?) {
        synchronized(failures) {
            if (failures.size >= PROFILE_FAILED_KEYS) failures.remove(failures.keys.first())
            failures[key] = until
        }
        if (until == null) profiles.failed(key)
    }

    fun request(key: String, token: String): Deferred<ClaudeAccountIdentity?> = active[key]
        ?: if (remembered(key)) CompletableDeferred(profiles.read(key)) else start(key, token)

    private fun start(key: String, token: String): Deferred<ClaudeAccountIdentity?> {
        val next = scope.async(dispatcher, start = CoroutineStart.LAZY) {
            val result = Cancellables.runCatchingCancellable { probe.result(token) }.getOrElse { failure ->
                log("[claude-profile] profile read failed (${failure::class.simpleName})\n")
                ClaudeProfileResult.Transient
            }
            when (result) {
                is ClaudeProfileResult.Verified -> profiles.observed(key, result.account)
                ClaudeProfileResult.Refused -> rememberFailure(key, null)
                ClaudeProfileResult.Transient -> rememberFailure(key, clock() + PROFILE_TRANSIENT_BACKOFF_MS)
            }
            (result as? ClaudeProfileResult.Verified)?.account ?: profiles.read(key)
        }
        next.invokeOnCompletion { active.remove(key, next) }
        val existing = active.putIfAbsent(key, next)
        return when {
            existing != null -> {
                next.cancel()
                existing
            }
            remembered(key) -> {
                next.cancel()
                CompletableDeferred(profiles.read(key))
            }
            else -> {
                next.start()
                next
            }
        }
    }
}

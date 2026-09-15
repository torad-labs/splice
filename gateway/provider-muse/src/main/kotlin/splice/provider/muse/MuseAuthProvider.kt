// NEW: Muse persisted inference-key provider with bounded re-mint holds and safe persistence.
package splice.provider.muse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.CredentialJson
import splice.core.auth.Credentials
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.spi.AccountCredentialIdentitySource
import splice.spi.AccountCredentialIdentitySource.CredentialEvidence
import splice.spi.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.spi.AccountCredentialIdentitySource.CredentialPresence
import splice.spi.CredentialLock
import splice.spi.SingleFlight
import java.nio.file.Path

private const val DEFAULT_RATE_HOLD_MS = 60_000L
private const val MAX_MINT_HOLD_MS = 3_600_000L
private const val DEFAULT_CACHE_MS = 30_000L

/** Reads and re-mints one Muse account's persisted inference key. */
public class MuseAuthProvider(
    private val authPath: Path,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val mintCall: MuseKeyMintCall,
    private val authCacheMs: Long = DEFAULT_CACHE_MS,
    private val prefetchScope: CoroutineScope? = null,
) : RefreshableAuthProvider, AccountCredentialIdentitySource {
    private val store = MuseCredentialStore(authPath, log, clock)
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidAccountLatch = InvalidGrantLatch()
    private val holds = MuseMintHolds(clock)
    private val oauth = MuseOAuth()
    private val persistedMintFields = setOf(
        "api_key",
        "access_token",
        "is_subs_active",
        "require_payment",
        "action_url",
        "require_payment_action_url",
        "subs_tier_id",
        "subs_tier_name",
        "subs_usage",
    )
    private val lockPathText = authPath.resolveSibling("${authPath.fileName}.lock").toString()
    private val lockLog = LogSink { message ->
        log(message.replace(lockPathText, "<muse-credential-lock>"))
    }

    init {
        prefetchScope?.coroutineContext?.get(Job)?.invokeOnCompletion { singleFlight.close() }
    }

    override suspend fun credentials(): Credentials? {
        val snapshot = store.read(authCacheMs) ?: return null
        return if (holds.blocksCredentials(snapshot)) {
            null
        } else {
            snapshot.apiKey?.let { Credentials.Bearer(it) }
        }
    }

    override suspend fun refresh(): Credentials? = singleFlight.run {
        Cancellables.runCatchingCancellable {
            CredentialLock.withLock(authPath, log = lockLog) {
                val snapshot = store.read() ?: return@withLock null
                val suppressed = invalidAccountLatch.isLatched(snapshot.identity) || holds.suppresses(snapshot)
                if (suppressed) {
                    null
                } else {
                    snapshot.accessToken?.let { exchange(it, snapshot) } ?: run {
                        log("[muse-auth] account access token missing; sign in again before refreshing")
                        null
                    }
                }
            }
        }.getOrElse {
            log("[muse-auth] credential lock unavailable; refresh skipped")
            null
        }
    }

    /**
     * Poller-only mint for `subs_usage`. Does not persist and does not take the credential lock.
     * Obeys the same holds as [refresh]: a live hold is a null with no POST; 429 and inactive
     * verdicts record those holds. Returns only the subs_usage object.
     */
    public suspend fun usageFields(): JsonObject? {
        val snapshot = store.read() ?: return null
        val held = invalidAccountLatch.isLatched(snapshot.identity) || holds.suppresses(snapshot)
        if (held) return null
        return snapshot.accessToken?.let { probeUsage(it, snapshot) }
    }

    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean =
        !oauth.isPlanTierRejection(body)

    override suspend fun describe(): AuthDescription {
        val snapshot = store.read()
        return AuthDescription(
            present = snapshot?.apiKey != null,
            kind = "muse-oauth",
            fields = buildMap {
                put("login", "device")
                put("auth_path", authPath.toString())
                snapshot?.let { current ->
                    putAll(holds.description(current))
                    if (invalidAccountLatch.isLatched(current.identity)) {
                        put("account_token", "invalid")
                    }
                }
            },
        )
    }

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialPresence(): CredentialPresence = credentialEvidence().presence

    override fun credentialEvidence(): CredentialEvidence = CredentialFileEvidenceReader.read(authPath)

    private suspend fun exchange(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
        allowChangedTokenRetry: Boolean = true,
    ): Credentials? {
        val attempt = mintOrHold(accessToken, snapshot) ?: return null
        return when (attempt) {
            is MuseMintAttempt.Granted -> granted(accessToken, attempt)
            is MuseMintAttempt.InvalidAccountToken ->
                invalidAccountToken(accessToken, allowChangedTokenRetry)
            is MuseMintAttempt.SubscriptionRequired,
            is MuseMintAttempt.RateLimited,
            is MuseMintAttempt.Denied,
            -> hold(snapshot, attempt)
        }
    }

    private suspend fun probeUsage(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
    ): JsonObject? {
        val attempt = mintOrHold(accessToken, snapshot) ?: return null
        return when (attempt) {
            is MuseMintAttempt.Granted -> attempt.key.fields["subs_usage"] as? JsonObject
            is MuseMintAttempt.InvalidAccountToken -> {
                invalidAccountToken(accessToken, allowChangedTokenRetry = false)
                null
            }
            is MuseMintAttempt.SubscriptionRequired,
            is MuseMintAttempt.RateLimited,
            is MuseMintAttempt.Denied,
            -> {
                hold(snapshot, attempt)
                null
            }
        }
    }

    private suspend fun granted(accessToken: String, attempt: MuseMintAttempt.Granted): Credentials? =
        if (persistGranted(accessToken, attempt.key)) {
            holds.clear()
            Credentials.Bearer(attempt.key.apiKey)
        } else {
            null
        }

    private suspend fun mintOrHold(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
    ): MuseMintAttempt? =
        Cancellables.runCatchingCancellable {
            mintCall(accessToken, MuseMintMode.REFRESH)
        }.getOrElse {
            log("[muse-auth] key mint transport failed")
            store.clearCache()
            holds.recordRetry(snapshot, DEFAULT_RATE_HOLD_MS)
            null
        }

    private suspend fun persistGranted(expectedAccessToken: String, key: MuseSubscriptionKey): Boolean {
        val current = store.read() ?: return false
        if (current.accessToken != expectedAccessToken) {
            log("[muse-auth] credential changed while key mint was in flight — minted key discarded")
            return false
        }
        val replacements = buildJsonObject {
            key.fields.forEach { (name, value) ->
                if (name in persistedMintFields) put(name, value)
            }
            put("api_key", JsonPrimitive(key.apiKey))
            put("access_token", JsonPrimitive(expectedAccessToken))
        }
        val merged = CredentialJson.mergedCredentialJson(current.fields, replacements)
        val persisted = Cancellables.runCatchingCancellable {
            currentCoroutineContext().ensureActive()
            SecureFile.writeAtomic0600(authPath, merged.toString())
            store.clearCache()
        }
        return persisted.fold(
            onSuccess = { true },
            onFailure = {
                log("[muse-auth] failed to persist the minted key; existing credential retained")
                false
            },
        )
    }

    private suspend fun invalidAccountToken(
        usedAccessToken: String,
        allowChangedTokenRetry: Boolean,
    ): Credentials? {
        val current = store.read()
        val currentAccessToken = current?.accessToken
        return when {
            currentAccessToken == usedAccessToken -> {
                invalidAccountLatch.latch(current.identity)
                store.clearCache()
                log("[muse-auth] account access token rejected; sign in again")
                null
            }
            allowChangedTokenRetry && currentAccessToken != null ->
                exchange(currentAccessToken, current, allowChangedTokenRetry = false)
            else -> null
        }
    }

    private fun hold(snapshot: MuseCredentialSnapshot, attempt: MuseMintAttempt): Credentials? {
        when (attempt) {
            is MuseMintAttempt.SubscriptionRequired -> {
                val actionUrl = oauth.safeActionOrigin(attempt.actionUrl)
                holds.recordInactive(snapshot, MAX_MINT_HOLD_MS, actionUrl)
                val action = actionUrl?.let { " — $it" }.orEmpty()
                log("[muse-auth] subscription inactive; retry held for 60 minutes$action")
            }
            is MuseMintAttempt.RateLimited -> {
                val holdMs = (attempt.retryAfterMs ?: DEFAULT_RATE_HOLD_MS)
                    .coerceIn(DEFAULT_RATE_HOLD_MS, MAX_MINT_HOLD_MS)
                holds.recordRetry(snapshot, holdMs)
                log("[muse-auth] key mint rate limited; retry held")
            }
            is MuseMintAttempt.Denied -> {
                holds.recordRetry(snapshot, MAX_MINT_HOLD_MS)
                log("[muse-auth] key mint denied; retry held for 60 minutes")
            }
            else -> Unit
        }
        store.clearCache()
        return null
    }
}

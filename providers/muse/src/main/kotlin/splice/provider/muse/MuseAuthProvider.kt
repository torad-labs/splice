// NEW: Muse persisted inference-key provider with bounded re-mint holds and safe persistence.
package splice.provider.muse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialEvidence
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import splice.upstream.credentials.CredentialLock
import splice.upstream.retry.SingleFlight
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

private const val DEFAULT_RATE_HOLD_MS = 60_000L
private const val MAX_MINT_HOLD_MS = 3_600_000L

/** Reads and re-mints one Muse account's persisted inference key. */
public class MuseAuthProvider(
    private val authPath: Path,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val mintCall: MuseKeyMintCall,
    private val authCacheMs: Long,
    private val prefetchScope: CoroutineScope? = null,
    flightContext: CoroutineContext = ProcessDispatchers().background(),
) : RefreshableAuthProvider, AccountCredentialIdentitySource {
    private val store = MuseCredentialStore(authPath, log, clock)
    private val singleFlight = SingleFlight<Credentials?>(flightContext)
    private val mintFlight = SingleFlight<MintFlightResult>(flightContext)
    private val invalidAccountLatch = InvalidGrantLatch()
    private val holds = MuseMintHolds(clock)
    private val oauth = MuseOAuth()
    private val mintPersistence = MuseMintPersistence()
    private val lockPathText = authPath.resolveSibling("${authPath.fileName}.lock").toString()
    private val lockLog = LogSink { message ->
        log(message.replace(lockPathText, "<muse-credential-lock>"))
    }

    init {
        prefetchScope?.coroutineContext?.get(Job)?.invokeOnCompletion {
            singleFlight.close()
            mintFlight.close()
        }
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
        val attempt = attemptFor(accessToken, snapshot) ?: return null
        return when (attempt) {
            is MuseMintAttempt.Granted ->
                if (persistGranted(accessToken, attempt.key)) {
                    holds.clear()
                    Credentials.Bearer(attempt.key.apiKey)
                } else {
                    null
                }
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
        val attempt = attemptFor(accessToken, snapshot) ?: return null
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

    private suspend fun attemptFor(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
    ): MuseMintAttempt? {
        val flown = mintOrHold(accessToken, snapshot)
        return if (flown.forToken(accessToken, snapshot)) {
            flown.attempt
        } else {
            mintOrHold(accessToken, snapshot, coalesce = false).attempt
        }
    }

    private suspend fun mintOrHold(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
        coalesce: Boolean = true,
    ): MintFlightResult {
        val mint = suspend {
            val attempt = Cancellables.runCatchingCancellable {
                mintCall(accessToken, MuseMintMode.REFRESH)
            }.getOrElse {
                log("[muse-auth] key mint transport failed")
                store.clearCache()
                holds.recordRetry(snapshot, DEFAULT_RATE_HOLD_MS)
                null
            }
            MintFlightResult(accessToken, snapshot.identity, attempt)
        }
        return if (coalesce) mintFlight.run { mint() } else mint()
    }

    private suspend fun persistGranted(expectedAccessToken: String, key: MuseSubscriptionKey): Boolean {
        val ctx = currentCoroutineContext()
        val ok = mintPersistence.persistGranted(
            authPath,
            expectedAccessToken,
            key,
            log,
            MuseMintWriteGuard { ctx.ensureActive() },
        )
        if (ok) store.clearCache()
        return ok
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

private data class MintFlightResult(
    val accessToken: String,
    val identity: CredentialFileIdentity?,
    val attempt: MuseMintAttempt?,
) {
    fun forToken(token: String, snapshot: MuseCredentialSnapshot): Boolean =
        accessToken == token && identity == snapshot.identity
}

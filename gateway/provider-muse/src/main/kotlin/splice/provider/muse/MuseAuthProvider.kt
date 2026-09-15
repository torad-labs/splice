// NEW: Muse persisted inference-key provider with bounded re-mint holds and safe persistence.
package splice.provider.muse

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

private const val AUTH_FAILURE_STATUS = 401
private const val DEFAULT_RATE_HOLD_MS = 60_000L
private const val MAX_MINT_HOLD_MS = 3_600_000L

/** Reads and re-mints one Muse account's persisted inference key. */
public class MuseAuthProvider(
    private val authPath: Path,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val mintCall: MuseKeyMintCall,
) : RefreshableAuthProvider, AccountCredentialIdentitySource {
    private val store = MuseCredentialStore(authPath, log)
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidAccountLatch = InvalidGrantLatch()
    private val holds = MuseMintHolds(clock)
    private val oauth = MuseOAuth()
    private val lockPathText = authPath.resolveSibling("${authPath.fileName}.lock").toString()
    private val lockLog = LogSink { message ->
        log(message.replace(lockPathText, "<muse-credential-lock>"))
    }

    override suspend fun credentials(): Credentials? {
        val snapshot = store.read() ?: return null
        return if (holds.blocksCredentials(snapshot)) {
            null
        } else {
            snapshot.apiKey?.let { Credentials.Bearer(it) }
        }
    }

    override suspend fun refresh(): Credentials? = singleFlight.run {
        Cancellables.runCatchingCancellable {
            CredentialLock.withLock(authPath, log = lockLog) { refreshLocked() }
        }.getOrElse {
            log("[muse-auth] credential lock unavailable; refresh skipped")
            null
        }
    }

    /**
     * Poller-only mint for `subs_usage`. Does not persist. Obeys the same holds as [refresh]:
     * a live hold is a null with no POST; 429 and inactive verdicts record those holds.
     */
    public suspend fun usageFields(): JsonObject? =
        Cancellables.runCatchingCancellable {
            CredentialLock.withLock(authPath, log = lockLog) {
                val snapshot = store.read() ?: return@withLock null
                val held = invalidAccountLatch.isLatched(snapshot.identity) || holds.suppresses(snapshot)
                if (held) return@withLock null
                val accessToken = snapshot.accessToken ?: return@withLock null
                val attempt = Cancellables.runCatchingCancellable {
                    mintCall(accessToken, MuseMintMode.REFRESH)
                }.getOrElse {
                    log("[muse-auth] key mint transport failed")
                    return@withLock null
                }
                when (attempt) {
                    is MuseMintAttempt.Granted -> attempt.key.fields
                    is MuseMintAttempt.InvalidAccountToken -> {
                        invalidAccountToken(accessToken, allowChangedTokenRetry = false)
                        null
                    }
                    is MuseMintAttempt.SubscriptionRequired -> {
                        subscriptionRequired(snapshot, attempt)
                        null
                    }
                    is MuseMintAttempt.RateLimited -> {
                        rateLimited(snapshot, attempt)
                        null
                    }
                    is MuseMintAttempt.Denied -> {
                        holds.recordRetry(snapshot, MAX_MINT_HOLD_MS)
                        log("[muse-auth] key mint denied; retry held for 60 minutes")
                        null
                    }
                }
            }
        }.getOrElse {
            log("[muse-auth] credential lock unavailable; usage probe skipped")
            null
        }

    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean = status == AUTH_FAILURE_STATUS

    override suspend fun describe(): AuthDescription {
        val snapshot = store.read()
        return AuthDescription(
            present = snapshot?.apiKey != null,
            kind = "muse-oauth",
            fields = buildMap {
                put("login", "device")
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

    private suspend fun refreshLocked(): Credentials? {
        val snapshot = store.read() ?: return null
        val suppressed = invalidAccountLatch.isLatched(snapshot.identity) || holds.suppresses(snapshot)
        if (suppressed) return null
        return snapshot.accessToken?.let { exchange(it, snapshot) } ?: run {
            log("[muse-auth] account access token missing; sign in again before refreshing")
            null
        }
    }

    private suspend fun exchange(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
        allowChangedTokenRetry: Boolean = true,
    ): Credentials? {
        val attempt = Cancellables.runCatchingCancellable {
            mintCall(accessToken, MuseMintMode.REFRESH)
        }.getOrElse {
            log("[muse-auth] key mint transport failed")
            return null
        }
        return when (attempt) {
            is MuseMintAttempt.Granted -> granted(accessToken, attempt)
            is MuseMintAttempt.InvalidAccountToken ->
                invalidAccountToken(accessToken, allowChangedTokenRetry)
            is MuseMintAttempt.SubscriptionRequired -> subscriptionRequired(snapshot, attempt)
            is MuseMintAttempt.RateLimited -> rateLimited(snapshot, attempt)
            is MuseMintAttempt.Denied -> {
                holds.recordRetry(snapshot, MAX_MINT_HOLD_MS)
                log("[muse-auth] key mint denied; retry held for 60 minutes")
                null
            }
        }
    }

    private fun granted(accessToken: String, attempt: MuseMintAttempt.Granted): Credentials? =
        if (persistGranted(accessToken, attempt.key)) {
            holds.clear()
            Credentials.Bearer(attempt.key.apiKey)
        } else {
            null
        }

    private fun persistGranted(expectedAccessToken: String, key: MuseSubscriptionKey): Boolean {
        val current = store.read() ?: return false
        if (current.accessToken != expectedAccessToken) {
            log("[muse-auth] credential changed while key mint was in flight — minted key discarded")
            return false
        }
        val replacements = buildJsonObject {
            key.fields.forEach { (name, value) ->
                val spliceOwned = name == "splice_auth_kind" || name == "splice_account_label"
                if (!spliceOwned) put(name, value)
            }
            put("api_key", JsonPrimitive(key.apiKey))
            put("access_token", JsonPrimitive(expectedAccessToken))
        }
        val persisted = Cancellables.runCatchingCancellable {
            val merged = CredentialJson.mergedCredentialJson(current.fields, replacements)
            SecureFile.writeAtomic0600(authPath, merged.toString())
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
                log("[muse-auth] account access token rejected; sign in again")
                null
            }
            allowChangedTokenRetry && currentAccessToken != null ->
                exchange(currentAccessToken, current, allowChangedTokenRetry = false)
            else -> null
        }
    }

    private fun subscriptionRequired(
        snapshot: MuseCredentialSnapshot,
        attempt: MuseMintAttempt.SubscriptionRequired,
    ): Credentials? {
        val actionUrl = oauth.safeActionOrigin(attempt.actionUrl)
        holds.recordInactive(snapshot, MAX_MINT_HOLD_MS, actionUrl)
        val action = actionUrl?.let { " — $it" }.orEmpty()
        log("[muse-auth] subscription inactive; retry held for 60 minutes$action")
        return null
    }

    private fun rateLimited(
        snapshot: MuseCredentialSnapshot,
        attempt: MuseMintAttempt.RateLimited,
    ): Credentials? {
        val holdMs = (attempt.retryAfterMs ?: DEFAULT_RATE_HOLD_MS).coerceIn(0L, MAX_MINT_HOLD_MS)
        holds.recordRetry(snapshot, holdMs)
        log("[muse-auth] key mint rate limited; retry held")
        return null
    }

}

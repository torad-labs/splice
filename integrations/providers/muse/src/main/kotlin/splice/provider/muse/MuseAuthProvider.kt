// NEW: Muse persisted inference-key provider with bounded re-mint holds and safe persistence.
// NO-EXPIRY-EXEMPT[2026-09-21]: the muse-oauth credential kind carries no expiry and no refresh
// token (MuseCredentialShape reads a flat access_token). That account token is not served to a
// backend — it is the input to key minting, and the minted inference key's lifetime is governed
// by the mint holds below, not by a cached ceiling. There is nothing here to age out, so SH-01's
// synthesized ceiling would force a refresh down a path that does not exist.
package splice.provider.muse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
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

internal const val DEFAULT_RATE_HOLD_MS = 60_000L
internal const val MAX_MINT_HOLD_MS = 3_600_000L

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
    private val singleFlight = SingleFlight<RefreshOutcome>(flightContext)
    private val mintFlight = SingleFlight<MintFlightResult>(flightContext)
    private val invalidAccountLatch = InvalidGrantLatch()
    private val holds = MuseMintHolds(clock)
    private val oauth = MuseOAuth()
    private val verdict = MuseMintVerdict(holds, oauth, store, log, clock)
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

    /** Muse's key mint can refuse this login, and its reason is the turn's ending (failure-handling slice 3): an
     *  inactive subscription says to renew it, a rate-limited mint is a soft limit until its hold ends, and a denied
     *  mint is forbidden. Before this the turn ended as NoLogin and told a signed-in person to sign in. Audit, Nov 9,
     *  2026: only a mint a turn asked for, after its key stopped working, records a refusal; the usage poll's mint and
     *  a mint that could not answer never do, so the stored key keeps serving while it works. */
    override suspend fun answer(): LoginAnswer {
        val snapshot = store.read(authCacheMs) ?: return LoginAnswer.Missing
        holds.refusal(snapshot)?.let { return LoginAnswer.Refused(it) }
        return snapshot.apiKey
            ?.takeUnless { holds.blocksCredentials(snapshot) }
            ?.let { LoginAnswer.Granted(Credentials.Bearer(it)) }
            ?: LoginAnswer.Missing
    }

    override suspend fun refresh(): Credentials? = (exchange() as? RefreshOutcome.Refreshed)?.credentials

    /** One key mint for a turn whose key stopped working, read whole by the turn's retry engine: a mint that could not
     *  answer is [RefreshOutcome.Unavailable], which the engine asks again, and never a hold (audit, Nov 9, 2026). */
    override suspend fun exchange(): RefreshOutcome = singleFlight.run {
        Cancellables.runCatchingCancellable {
            CredentialLock.withLock(authPath, log = lockLog) {
                val snapshot = store.read() ?: return@withLock RefreshOutcome.Rejected("no readable muse credential")
                val accessToken = snapshot.accessToken
                when {
                    invalidAccountLatch.isLatched(snapshot.identity) -> RefreshOutcome.Rejected(INVALID_GRANT_REASON)
                    holds.suppresses(snapshot) -> RefreshOutcome.Rejected("key mint held")
                    // V4-292: only a missing token says so; a failed exchange ran `?:` into this line too.
                    accessToken == null -> {
                        log("[muse-auth] account access token missing; sign in again before refreshing")
                        RefreshOutcome.NoRefreshToken
                    }
                    else -> mint(accessToken, snapshot)
                }
            }
        }.getOrElse { failure ->
            lockLog("[muse-auth] credential lock unavailable (${SafeFailureText.render(failure)}); refresh skipped")
            RefreshOutcome.ReadFailed(failure)
        }
    }

    /**
     * Poller-only mint for `subs_usage`. Does not persist and does not take the credential lock.
     * Obeys the holds [exchange] records: a live hold is a null with no POST. What the poll's own mint answers records
     * no hold and no refusal (audit, Nov 9, 2026): one refused or failed poll held every turn for up to an hour though
     * the stored key still worked, so the poller only says it in the log, and a turn whose key stops working renews it.
     * Returns only the subs_usage object, and an empty object when a granted mint
     * carries none: Muse answered and has no usage for this account, which clears the bars (Oct 10, 2026), where a
     * null is only "nothing was asked".
     */
    public suspend fun usageFields(): JsonObject? {
        val snapshot = store.read() ?: return null
        // The probe and its reading are one function since slice 3: this class gained answer(), and splitting the
        // two halves of one poll is what pushed it over the function ceiling, so the holds read as one line here.
        val accessToken = snapshot.accessToken?.takeUnless {
            invalidAccountLatch.isLatched(snapshot.identity) || holds.suppresses(snapshot)
        } ?: return null
        return when (val attempt = attemptFor(accessToken, snapshot)) {
            is MuseMintAttempt.Granted -> attempt.key.fields["subs_usage"] as? JsonObject ?: JsonObject(emptyMap())
            is MuseMintAttempt.InvalidAccountToken -> {
                invalidAccountToken(accessToken, allowChangedTokenRetry = false)
                null
            }
            is MuseMintAttempt.SubscriptionRequired,
            is MuseMintAttempt.RateLimited,
            is MuseMintAttempt.Denied,
            is MuseMintAttempt.Unavailable,
            -> {
                log("[muse-auth] usage poll mint answered ${attempt::class.simpleName}; no hold, the stored key serves")
                null
            }
        }
    }

    // Failure-handling slice 3: the plan-tier rejection this used to veto on is read as what it is by MuseReplies
    // (a plan limit, never a refused login), so no refresh is planned for it and this provider carries no rule
    // about a body. The default answer — refresh a refused login — is this provider's answer.

    override suspend fun describe(): AuthDescription = store.describe(holds, invalidAccountLatch)

    override fun observedCredentialKey(): String? = store.observedCredentialKey()

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialPresence(): CredentialPresence = credentialEvidence().presence

    override fun credentialEvidence(): CredentialEvidence = CredentialFileEvidenceReader.read(authPath)

    private suspend fun mint(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
        allowChangedTokenRetry: Boolean = true,
    ): RefreshOutcome = when (val attempt = attemptFor(accessToken, snapshot)) {
        is MuseMintAttempt.Granted -> {
            val ctx = currentCoroutineContext()
            val guard = MuseMintWriteGuard { ctx.ensureActive() }
            if (mintPersistence.persistGranted(authPath, accessToken, attempt.key, log, guard)) {
                store.clearCache()
                holds.clear()
                RefreshOutcome.Refreshed(Credentials.Bearer(attempt.key.apiKey))
            } else {
                RefreshOutcome.Rejected("minted key could not be stored")
            }
        }
        is MuseMintAttempt.InvalidAccountToken ->
            invalidAccountToken(accessToken, allowChangedTokenRetry)
        is MuseMintAttempt.SubscriptionRequired,
        is MuseMintAttempt.RateLimited,
        is MuseMintAttempt.Denied,
        is MuseMintAttempt.Unavailable,
        -> verdict.hold(snapshot, attempt)
    }

    private suspend fun attemptFor(
        accessToken: String,
        snapshot: MuseCredentialSnapshot,
    ): MuseMintAttempt {
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
            }.getOrElse { failure ->
                // Audit, Nov 9, 2026: a mint the request never reached is no verdict on the account and holds nothing.
                store.clearCache()
                MuseMintAttempt.Unavailable("key mint transport failed (${SafeFailureText.render(failure)})")
            }
            MintFlightResult(accessToken, snapshot.identity, attempt)
        }
        return if (coalesce) mintFlight.run { mint() } else mint()
    }

    private suspend fun invalidAccountToken(
        usedAccessToken: String,
        allowChangedTokenRetry: Boolean,
    ): RefreshOutcome {
        val current = store.read()
        val currentAccessToken = current?.accessToken
        return when {
            currentAccessToken == usedAccessToken -> {
                invalidAccountLatch.latch(current.identity)
                store.clearCache()
                log("[muse-auth] account access token rejected; sign in again")
                RefreshOutcome.Rejected(INVALID_GRANT_REASON)
            }
            allowChangedTokenRetry && currentAccessToken != null ->
                mint(currentAccessToken, current, allowChangedTokenRetry = false)
            currentAccessToken == null -> {
                log("[muse-auth] account access token rejected, and the credential file holds none now; sign in again")
                RefreshOutcome.NoRefreshToken
            }
            else -> {
                log(
                    "[muse-auth] account access token rejected, and the credential file's token changed since; " +
                        "the next refresh tries the new one",
                )
                RefreshOutcome.Rejected("account access token changed during the mint")
            }
        }
    }
}

private data class MintFlightResult(
    val accessToken: String,
    val identity: CredentialFileIdentity?,
    val attempt: MuseMintAttempt,
) {
    /** The token is the single-flight KEY, so this type holds one by construction. */
    override fun toString(): String =
        "MintFlightResult(accessToken=<redacted>, identity=$identity, attempt=$attempt)"

    fun forToken(token: String, snapshot: MuseCredentialSnapshot): Boolean =
        accessToken == token && identity == snapshot.identity
}

// NEW: audit, Nov 9, 2026 — ONE renewal ladder for every login splice renews from a credential file.
//
// codex, grok, kimi and the Claude accounts splice adds each carried their own copy of the same ladder (serve, renew in
// the background, wait near expiry, stat, latch, lock, exchange, persist), and the copies drifted: a failed write threw
// the granted pair away (codex, grok, kimi), a token endpoint that could not answer on an expired token became "no
// login" with no retry (codex, grok, kimi), a lock or file error escaped as an upstream connection reset (codex, grok,
// kimi), and the Claude copy failed closed on a valid token, lost a rotated refresh token on a failed write, and
// answered a 401 with the very token that drew it. The ladder lives here once, with typed outcomes end to end. What
// stays with each vendor is a vendor fact: the file's shape, how its expiry reads, the token call and how a grant merges
// into the document ([RenewalStage]), and its windows ([RenewalWindows]).
package splice.upstream.credentials

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.auth.CredentialFileDigest
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.StateMemory
import splice.core.util.WallClock
import splice.upstream.retry.SingleFlight
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The vendor's half of one renewal, run while the credential lock is held: read the file again ([TokenRenewal.text]),
 *  adopt a rotation that landed since [prior] was served (G1), else exchange the refresh token and
 *  [TokenRenewal.persist] what the endpoint granted. [prior] is the token this process last served, taken before the
 *  lock. */
public fun interface RenewalStage {
    public suspend fun renew(prior: String?): RefreshOutcome
}

/** The token a provider read from its credential file now, and when it expires (null: no expiry is known). */
public data class HeldToken(val credentials: Credentials, val expiresAtMs: Long?)

/** When a held token is renewed: within [proactiveMs] of its expiry a renewal starts in the background while the token
 *  is still served (G17); within [floorMs], or where no background scope was given, the lookup waits for it. */
public data class RenewalWindows(val proactiveMs: Long, val floorMs: Long)

/** One login's renewal. [prefetchScope] owns background renewals and, when it ends, the renewal in flight. */
public class TokenRenewal(
    private val authPath: Path,
    private val tag: String,
    private val clock: WallClock,
    private val log: LogSink,
    prefetchScope: CoroutineScope?,
    private val stage: RenewalStage,
) {
    private val flight = SingleFlight<RefreshOutcome>()
    private val background = prefetchScope?.let { BackgroundCredentialRefresh(it, tag, log) }
    private val retained = RetainedCredentialWrite(authPath, tag, log)
    private val statFailures = StateMemory()

    /** G15: a confirmed invalid_grant against this exact file state is not posted again; a new file state lifts it. */
    public val latch: InvalidGrantLatch = InvalidGrantLatch()

    /** Where every reader of the credential file reads it: the renewed document a failed write still holds, else the
     *  file itself. */
    public val text: CredentialText get() = retained

    @Volatile
    private var lastServed: String? = null

    init {
        // Lifecycle ownership: when the scope ends (Daemon.stop cancels probeScope), the renewal in flight is cancelled
        // so it cannot write a token after shutdown. One caller's cancellation only cancels its own await.
        prefetchScope?.coroutineContext?.get(Job)?.invokeOnCompletion { flight.close() }
    }

    /** What a login lookup answers for [held], the token the provider just read: served as it is outside the windows,
     *  served while a background renewal runs inside the proactive one, and waited for inside the floor, where a token
     *  that is still alive is served whatever the renewal said, and an expired one answers the renewal's own outcome. */
    public suspend fun answer(held: HeldToken?, windows: RenewalWindows): LoginAnswer {
        if (retained.waiting) flushLocked()
        held ?: return LoginAnswer.Missing
        val expiresAt = held.expiresAtMs ?: return serve(held.credentials)
        val left = expiresAt - clock()
        return when {
            left >= windows.proactiveMs -> serve(held.credentials)
            left >= windows.floorMs && background != null -> {
                background.launch { (exchange() as? RefreshOutcome.Refreshed)?.credentials }
                serve(held.credentials)
            }
            else -> waited(held, expiresAt)
        }
    }

    /** A token a vendor rule serves without a renewal (grok's ineffective-renewal backoff); remembered as served. */
    public fun serve(credentials: Credentials): LoginAnswer {
        lastServed = secretOf(credentials)
        return LoginAnswer.Granted(credentials)
    }

    /** One token exchange, single-flight, logged once inside the flight (credentialsOrNull is the one logging
     *  flatten), and read whole by the turn's retry engine (failure-handling slice 4). */
    public suspend fun exchange(): RefreshOutcome {
        val outcome = flight.run { renewed().also { it.credentialsOrNull(tag, log) } }
        if (outcome is RefreshOutcome.Refreshed) lastServed = secretOf(outcome.credentials)
        return outcome
    }

    /** Writes the document a grant produced. The endpoint has already spent the old refresh token, so a write that
     *  fails keeps the grant: [text] serves it and the next renewal or lookup writes it again, and the provider's
     *  read-back returns the renewed token either way. */
    public fun persist(document: String) {
        retained.write(document)?.let { log(retained.failedLine(it)) }
    }

    /** The credential file's identity (DR-176, V4-70: its mtime, size and content digest), or null when it cannot be
     *  read, which the latch treats as unknown and never suppresses on. One line per change of failure. */
    public fun identity(): CredentialFileIdentity? = Cancellables.runCatchingCancellable {
        CredentialFileIdentity(
            Files.getLastModifiedTime(authPath).toMillis(),
            Files.size(authPath),
            CredentialFileDigest.of(authPath),
        )
    }.onSuccess {
        statFailures.clear()
    }.onFailure {
        val state = "${SafeFailureText.render(it)} link=${Files.isSymbolicLink(authPath)}"
        if (statFailures.isNews(state)) {
            log(
                "[$tag] failed to stat $authPath identity: ${SafeFailureText.render(it)}; " +
                    "invalid_grant latch check skipped",
            )
        }
    }.getOrNull()

    private suspend fun waited(held: HeldToken, expiresAtMs: Long): LoginAnswer {
        val outcome = exchange()
        return when {
            outcome is RefreshOutcome.Refreshed -> LoginAnswer.Granted(outcome.credentials)
            clock() < expiresAtMs -> serve(held.credentials)
            outcome == RefreshOutcome.NoCredentialsFile -> LoginAnswer.Missing
            else -> LoginAnswer.Unrenewed(outcome)
        }
    }

    // Staged, each stage owning its failures. DR-59: logged-out is PROVEN by the stat (only NoSuch with no NOFOLLOW
    // entry), never an exists() pre-gate. G15: a latched invalid_grant is answered before any read or post. G1: the
    // vendor stage runs inside the cross-process lock. Audit, Nov 9, 2026: a lock that cannot be taken (a read-only
    // folder, a lock file another user owns, no file descriptors left) is a local read failure with its own line,
    // never a throw that the turn would report as the upstream resetting the connection.
    private suspend fun renewed(): RefreshOutcome {
        val statFailure = Cancellables.runCatchingCancellable { Files.getLastModifiedTime(authPath) }.exceptionOrNull()
        if (statFailure != null) {
            val absent = statFailure is NoSuchFileException && !Files.exists(authPath, LinkOption.NOFOLLOW_LINKS)
            return if (absent) RefreshOutcome.NoCredentialsFile else RefreshOutcome.ReadFailed(statFailure)
        }
        val identity = identity()
        if (latch.isLatched(identity)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val prior = lastServed
        val outcome = Cancellables.runCatchingCancellable {
            // AUTH-002: the daemon log hears the lock's contention and its bounded unlocked degrade.
            CredentialLock.withLock(authPath, log = log) {
                retained.flush()
                stage.renew(prior)
            }
        }.getOrElse { RefreshOutcome.ReadFailed(it) }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) latch.latch(identity)
        return outcome
    }

    /** A renewed document waits to be written: written again under the lock, so it never lands over a peer's write. */
    private suspend fun flushLocked() {
        Cancellables.runCatchingCancellable { CredentialLock.withLock(authPath, log = log) { retained.flush() } }
            .onFailure {
                log("[$tag] credential lock unavailable (${SafeFailureText.render(it)}); the renewed credential waits")
            }
    }

    private fun secretOf(credentials: Credentials): String? = when (credentials) {
        is Credentials.Bearer -> credentials.token
        is Credentials.ApiKey -> credentials.key
        Credentials.ClientForwarded -> null
    }
}

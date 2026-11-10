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
 *  adopt a rotation that landed since [prior] was read (G1), else exchange the refresh token and
 *  [TokenRenewal.persist] what the endpoint granted. [prior] is the token the renewal was asked about, taken before the
 *  lock: the one a lookup held when it saw a renewal was due, or, for a 401, the one the refused request sent. */
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

    /** The token this login last served: what a renewal is asked about when its caller names no token of its own (a
     *  probe's, an operator's). A refused request names its own, never this one, which another turn moves. */
    @Volatile
    private var lastHeld: String? = null

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
                val seen = secretOf(held.credentials)
                background.launch { (exchangeSeen(seen) as? RefreshOutcome.Refreshed)?.credentials }
                serve(held.credentials)
            }
            else -> waited(held, expiresAt)
        }
    }

    /** A token a vendor rule serves without a renewal (grok's ineffective-renewal backoff); remembered as served. */
    public fun serve(credentials: Credentials): LoginAnswer {
        lastHeld = secretOf(credentials)
        return LoginAnswer.Granted(credentials)
    }

    /** One token exchange, single-flight, logged once inside the flight (credentialsOrNull is the one logging
     *  flatten), asked about the token this login last held: a probe's or an operator's renewal. */
    public suspend fun exchange(): RefreshOutcome = exchangeSeen(lastHeld)

    /** The exchange a refused request asks for, read whole by the turn's retry engine (failure-handling slice 4).
     *  [refused] is the credential that request actually sent (null: none was sent, and the last token held stands
     *  in). Asked about that token, never the last one served: a second 401 for a token a first 401 already rotated
     *  away adopts the pair on file instead of spending its refresh token again (Nov 10, 2026). */
    public suspend fun exchange(refused: Credentials?): RefreshOutcome =
        exchangeSeen(refused?.let(::secretOf) ?: lastHeld)

    /** One renewal for a caller that last saw [seen]: the token its lookup read, or the one a refused request sent
     *  (null: none known, so the stage rotates whatever is on file). A caller that arrives after a renewal ended starts
     *  the next one, and that one must tell from [seen] that the renewal it missed already replaced the token the
     *  caller holds. A caller joins a renewal in flight only when it was started about the same token: one started
     *  about another token decides nothing for it, so the caller waits for it to end and asks about its own. */
    private suspend fun exchangeSeen(seen: String?): RefreshOutcome {
        val outcome = flight.run(seen) { renewed(seen).also { it.credentialsOrNull(tag, log) } }
        if (outcome is RefreshOutcome.Refreshed) lastHeld = secretOf(outcome.credentials)
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
        // The renewal is asked about the token this lookup read, not the one last served: a renewal that landed after
        // the read and before this call has already replaced it, and its grant is this lookup's answer, never a second
        // rotation that spends the refresh token the first one just issued.
        val outcome = exchangeSeen(secretOf(held.credentials))
        return when {
            outcome is RefreshOutcome.Refreshed -> LoginAnswer.Granted(outcome.credentials)
            clock() < expiresAtMs -> serve(held.credentials)
            outcome == RefreshOutcome.NoCredentialsFile -> LoginAnswer.Missing
            // Nothing is sent: the turn's next exchange is asked about the token this lookup held.
            else -> LoginAnswer.Unrenewed(outcome, held.credentials)
        }
    }

    // Staged, each stage owning its failures. DR-59: logged-out is PROVEN by the stat (only NoSuch with no NOFOLLOW
    // entry), never an exists() pre-gate. G15: a latched invalid_grant is answered before any read or post. G1: the
    // vendor stage runs inside the cross-process lock. Audit, Nov 9, 2026: a lock that cannot be taken (a read-only
    // folder, a lock file another user owns, no file descriptors left) is a local read failure with its own line,
    // never a throw that the turn would report as the upstream resetting the connection.
    private suspend fun renewed(seen: String?): RefreshOutcome {
        val statFailure = Cancellables.runCatchingCancellable { Files.getLastModifiedTime(authPath) }.exceptionOrNull()
        if (statFailure != null) {
            val absent = statFailure is NoSuchFileException && !Files.exists(authPath, LinkOption.NOFOLLOW_LINKS)
            return if (absent) RefreshOutcome.NoCredentialsFile else RefreshOutcome.ReadFailed(statFailure)
        }
        if (latch.isLatched(identity())) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        return Cancellables.runCatchingCancellable {
            // AUTH-002: the daemon log hears the lock's contention and its bounded unlocked degrade.
            CredentialLock.withLock(authPath, log = log) {
                retained.flush()
                val read = identity()
                stage.renew(seen).also { latchRefusal(it, read) }
            }
        }.getOrElse { RefreshOutcome.ReadFailed(it) }
    }

    /** G15, keyed on the state whose refresh token was refused (Nov 10, 2026). The latch used to take the file's
     *  identity before the lock, so a renewal that waited out a peer's rotation latched a state it never posted from,
     *  and an exchange whose stage re-read and posted a newer pair latched the older one. Now [read] is the file as the
     *  stage found it, under the lock, and the latch is set only when the file still stands so after the refusal: a
     *  file that moved meanwhile holds a pair this refusal says nothing about, so nothing is latched against it. */
    private fun latchRefusal(outcome: RefreshOutcome, read: CredentialFileIdentity?) {
        if (outcome !is RefreshOutcome.Rejected || outcome.reason != INVALID_GRANT_REASON) return
        if (read != null && identity() == read) latch.latch(read)
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

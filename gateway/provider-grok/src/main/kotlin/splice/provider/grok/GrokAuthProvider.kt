// NEW: xAI Grok OAuth runtime auth — the SuperGrok/X-Premium+ browser login (GrokOAuth), NOT an
// api key. Mirrors CodexAuthProvider: cached ~/.grok/auth.json read (path+mtime+TTL), single-flight
// refresh (grant_type=refresh_token, 0600 write, cache invalidation), masked introspection.
// Grok tokens carry no account id (no ChatGPT-Account-ID header), so Bearer.accountId is null.
// PROACTIVE refresh (grok-dead-head incident, 2026-07-18): xAI reports an expired token as 403
// (not 401), so the reactive 401-refresh path never fired and the head served a dead token
// until manual re-login. Like KimiAuthProvider: when the file's `expires` (ms epoch, written by
// the official grok CLI and by us) is within the proactive window, refresh BEFORE serving; a
// failed refresh on a not-yet-expired token still serves the current one.
// TWO-TIER proactive refresh (G17, 2026-07-19): a blocking refresh inside the whole 5-minute
// window stalls every request that lands in it (UpstreamClient.post() calls credentials()
// synchronously per attempt). Above STALE_FLOOR_MS, kick a single-flight refresh on an owned
// background scope and serve the current token immediately; only below the floor — close enough
// to hard expiry that risking a stale token is worse than the wait — do we still block, exactly
// as before.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
// Synthesized expiry for missing `expires` (G18, 2026-07-19): a file without a top-level `expires`
// (legacy shape, or a foreign CLI write that stripped it) was treated as never-expiring — no
// proactive refresh, no eventual expiry. readSnapshot() now synthesizes expiresAtMs = mtime + 4h
// so those files still age out and re-refresh through the same tiers above.
package splice.provider.grok

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialExpiry
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
import splice.core.auth.SYNTHETIC_EXPIRY_TTL_MS
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.core.util.WallClockIso
import splice.core.wire.HttpStatus
import splice.spi.AccountCredentialIdentitySource
import splice.spi.AccountCredentialIdentitySource.CredentialEvidence
import splice.spi.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.spi.AccountCredentialIdentitySource.CredentialPresence
import splice.spi.CredentialLock
import splice.spi.LifecycleScope
import splice.spi.ProcessDispatchers
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val LOG_TAG = "grok-auth"

// The one status whose auth meaning xAI overloads (expiry AND billing), so the one status a
// freshness judgement can arbitrate, is HttpStatus.FORBIDDEN — V4-135 reads it from the single
// declaration site instead of a local copy. See allowRefreshAfterFailure.

// SH-02(b): CLIProxyAPI's refreshIneffectiveBackoff value — long enough to stop a tight
// success/re-check loop, short enough that a genuinely recovering endpoint retries soon.
private const val REFRESH_INEFFECTIVE_BACKOFF_MS = 30_000L
private const val MS_PER_S = 1000L

/** Refresh this long before `expires` — well inside a 6h grok token, generous vs clock skew. */
private const val PROACTIVE_WINDOW_MS = 300_000L

/** Below this, block instead of prefetching: comfortably above the refreshCall's measured
 *  RTT (sub-second) and well below the 300s window, so most of the window stays non-blocking. */
private const val STALE_FLOOR_MS = 30_000L

public class GrokAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val nowIso: WallClockIso = WallClockIso { Instant.ofEpochMilli(System.currentTimeMillis()).toString() },
    /** POST grant_type=refresh_token to auth.x.ai's token URL; returns the classified attempt. */
    private val refreshCall: RefreshCall<GrokRefreshedTokens>,
    // Injectable so the daemon runs the prefetch LAUNCH on probeScope and tests stay self-contained.
    // SingleFlight isolates the shared refresh from any single request's await cancellation (a peer
    // awaiting it must not be killed); the init block below ties that shared refresh to THIS scope's
    // lifetime, so Daemon.stop (probeScope.cancel) cancels an in-flight refresh instead of letting
    // it write the token file post-shutdown (review 2026-07-23).
    // HD-19: LifecycleScope is the NAMED owner the bare CoroutineScope(...) factory lacked, and it
    // applies the same SupervisorJob on the same background dispatcher — identical context, and the
    // daemon still overrides this default with its own probeScope in production.
    private val prefetchScope: CoroutineScope = LifecycleScope(ProcessDispatchers().background()),
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: LogSink = LogSink(DaemonLog::write),
) : RefreshableAuthProvider, AccountCredentialIdentitySource {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

    // G15: the mtime probe lives on its own collaborator, not on this class. Measured, not assumed:
    // GrokAuthProvider holds 14 non-override functions and TooManyFunctions flags at 15 (overrides
    // are ignored), so folding grokAuthMtimeOrNull in here fails the build. HD-M5 red-proved this
    // with a synthetic 15th member; codex/kimi sit at 13 and DO host their own probe.
    private val authJson = GrokAuthJson(
        authPath,
        json,
        log,
        nowIso,
        clock,
        GrokAuthJson.SynthesizeExpiry { mtimeMs, nowMs -> synthesizeExpiry(mtimeMs, nowMs) },
    )

    private fun synthesizeExpiry(mtimeMs: Long, nowMs: Long): Long =
        CredentialExpiry.synthesizedExpiryMs(mtimeMs, nowMs)
    private val authFile = GrokAuthDescribe(authPath, authJson, invalidGrantLatch, log, refreshCall)

    /** RULE 3's sentence builder. Stateless, so one instance is enough (see GrokOAuth's file-scope
     *  Json comment for why the parser it shares is file scope rather than per-instance). */
    private val oauth = GrokOAuth()

    init {
        // Lifecycle ownership: when prefetchScope ends (Daemon.stop cancels probeScope), cancel the
        // shared refresh so it cannot persist a token after shutdown. Per-request cancellation is
        // unaffected — it only cancels that caller's await, never the SingleFlight scope.
        prefetchScope.coroutineContext[Job]?.invokeOnCompletion { singleFlight.close() }
    }

    // SH-02(b): set when a refresh persisted an expiry still inside the stale floor; the blocking
    // tier serves the current token until the backoff lapses. (c): counted for the dashboard.
    @Volatile
    // MIN_VALUE/2, not MIN_VALUE: `clock() - MIN_VALUE` overflows negative and would read as
    // "backoff active" from boot with small/injected clocks.
    private var lastIneffectiveRefreshAtMs: Long = Long.MIN_VALUE / 2
    private val ineffectiveRefreshes = java.util.concurrent.atomic.AtomicLong()

    /** SH-02(c): how many refreshes succeeded without satisfying the tier logic — nonzero here is
     *  the early warning that used to arrive as provider-side credential death. */
    internal val ineffectiveRefreshCount: Long get() = ineffectiveRefreshes.get()

    // Three tiers by remaining time-to-expiry, as a single if/else-if/else expression (not `when`,
    // not extra member functions — GrokAuthProvider is already at its detekt function-count budget):
    // outside the proactive window, serve as-is; above the stale floor, prefetch in the background
    // and serve the current token (G17); below the floor, block for a confirmed-fresh token exactly
    // as before G17 (on a failed refresh still serve the current token if it hasn't actually expired).
    override suspend fun credentials(): Credentials? {
        val snap = authJson.readSnapshot(authCacheMs) ?: return null
        val current = Credentials.Bearer(snap.access, null)
        val expiresAt = snap.expiresAtMs
        // expiresAt is always populated now (real, or synthesized off mtime by readSnapshot — G18);
        // still outside the proactive window means serve as-is. The null branch below stays as
        // defensive-only dead code for a future caller that constructs Snapshot directly.
        return if (expiresAt == null || expiresAt - clock() >= PROACTIVE_WINDOW_MS) {
            current
        } else if (expiresAt - clock() >= STALE_FLOOR_MS) {
            // prefetch tier (G17): kick a single-flight refresh in the background, serve the CURRENT
            // token now. singleFlight still dedups concurrent entrants to one network call;
            // credentialsOrNull still owns the one logging flatten (discipline L3).
            prefetchScope.launch { singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) } }
            current
        } else if (clock() - lastIneffectiveRefreshAtMs < REFRESH_INEFFECTIVE_BACKOFF_MS) {
            // SH-02(b): the last refresh succeeded without advancing past the stale floor — another
            // one would too. Serve the current token through the backoff window instead of burning
            // a rotating refresh token per request.
            //
            // DR-146: but only while it is actually ALIVE. This branch served `current`
            // unconditionally, where the stale-floor branch three lines below has always refused a
            // token past its own expiry (`current.takeIf { clock() < expiresAt }`). A sub-floor
            // grant arms this backoff, and once that token passes its expiry INSIDE the window a
            // known-dead token went to UpstreamClient, which spent a real upstream call to collect
            // a 403 and then burned the single-flight refresh anyway — so the stated goal of not
            // burning a rotating refresh token per request was not achieved on the reactive path,
            // and each request also cost a wasted round trip.
            current.takeIf { clock() < expiresAt }
        } else {
            // stale floor: too close to hard expiry to risk it — block for a confirmed-fresh token,
            // same as pre-G17 behavior.
            val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }
            refreshed ?: current.takeIf { clock() < expiresAt }
        }
    }

    override suspend fun refresh(): Credentials? =
        singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }

    /**
     * RULE 1, the structural half (2026-09-16 operator report: the grok login page kept reopening).
     *
     * A 403 on a credential that is DEMONSTRABLY FINE cannot be an expiry, whatever the body says.
     * Vetoing the refresh here kills the whole class at once — no refresh, no dead credential, no
     * sign-in, no browser — and it needs no vendor strings, so the NEXT unrecognised 403 code is
     * covered too.
     *
     * ONLY A REAL `expires` MAY VETO (V4-38 redo). The first version read [GrokAuthJson.readSnapshot],
     * which never returns a null expiry: for a file that carries none (legacy shape, or a foreign CLI
     * write that stripped it) it SYNTHESIZES mtime + 4h (G18/SH-01, GrokAuthJson lines 99-104). That
     * ceiling is a statement about staleness, not about validity, and reading it as proof of freshness
     * suppressed the refresh on a genuine 403 expiry for up to four hours — precisely the 2026-07-18
     * grok-dead-head shape this provider exists to prevent, and the exact inversion
     * SynthesizedExpiry.kt:5 forbids: it "can force an extra refresh, never suppress one".
     * [GrokAuthJson.parseSnapshot] is the seam that already separates the two — it is what
     * readSnapshot calls BEFORE applying the synthesis, and it reports the file's `expires` as null
     * when the file has none — so the veto asks it instead. It also re-reads rather than serving the
     * TTL cache, which is the right side to err on for a judgement made once per auth failure, and it
     * THROWS where readSnapshot classifies, so the catch is here.
     *
     * NEVER BELOW STATUS QUO, on three counts: a token at or inside the proactive window is NOT
     * demonstrably fine; a file with no declared `expires` is NOT demonstrably fine; and an unreadable
     * file proves nothing. All three fall through to the pre-V4-38 behaviour — the refresh runs.
     *
     * RULE 3 appears here only as a SENTENCE: a recognised entitlement body is logged with its cause
     * and the vendor's own top-up link. Those strings never reach the decision below.
     *
     * A block body with a local, not a helper: this class sits at detekt's function budget (14
     * non-override functions; TooManyFunctions flags at 15), and overrides are exempt.
     */
    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean {
        // 403 ONLY. xAI reports an expired token as 403, which is what makes a freshness judgement
        // meaningful here; a 401 is the server contradicting the file, and a revoked token can 401
        // while the file still reads hours out — vetoing that refresh would serve a dead token and
        // REGRESS, not protect. The only statuses reaching this call are 401 and 403 (the transport
        // consults the veto solely for `isAuthRefreshableFailure`), so this is the whole surface.
        if (status != HttpStatus.FORBIDDEN) return true
        // The DECLARED expiry — the file's own `expires` — never a synthesized ceiling. parseSnapshot
        // rethrows anything that is not proven absence, so the guard is the caller's here just as it
        // is inside readSnapshot; an unreadable file yields null and vetoes nothing.
        val declaredExpiryMs = Cancellables.runCatchingCancellable { authJson.parseSnapshot() }
            .onFailure {
                log(
                    "[$LOG_TAG] auth.json unreadable while judging a $status — no veto, refresh runs: " +
                        SafeFailureText.render(it),
                )
            }
            .getOrNull()?.expiresAtMs
        val demonstrablyFresh =
            declaredExpiryMs != null && declaredExpiryMs - clock() >= PROACTIVE_WINDOW_MS
        if (demonstrablyFresh) {
            val sentence = oauth.entitlementSentence(body)
                ?: "body not recognised as an entitlement rejection"
            log(
                "[$LOG_TAG] upstream $status on a credential valid for another " +
                    "${(declaredExpiryMs - clock()) / MS_PER_S}s — not an expiry, so NO refresh and " +
                    "no sign-in. $sentence",
            )
        }
        return !demonstrablyFresh
    }

    /**
     * V4-73: grok's spent account is a 403 whose body names the billing wall
     * (`personal-team-blocked:spending-limit`), and until this override existed the transport
     * classified it as a terminal invalid_request — bypassing the cooldown, the account pool and
     * every client retry, when the operator's law is that credits exhaustion is retried until the
     * credits are back. The phrase list stays HERE, in the vendor module (SEPARATION); the port is
     * the neutral question and this is the only place that answers it with vendor spelling.
     *
     *  403 ONLY, and only when [GrokOAuth.isEntitlementRejection] recognises the body — the same
     *  list V4-38 uses for the refresh veto. An UNRECOGNISED 403 keeps today's behaviour exactly,
     *  including the freshness rule above: this rewrite never widens what counts as a quota wall.
     */
    override fun isQuotaExhausted(status: Int, body: String): Boolean =
        status == HttpStatus.FORBIDDEN && oauth.isEntitlementRejection(body)

    // Sealed per-mode outcome (discipline L3): a dead refresh token, a transport blip, and a
    // corrupt file are DIFFERENT stories; credentialsOrNull is the single logging flatten.
    // Staged (read → exchange → persist), each stage owning its own failure branches.
    // G1: capture what THIS process last served BEFORE the lock, then re-read authoritatively INSIDE
    // it — so a peer's rotation (landed while we waited on the lock) is seen, not overwritten.
    // G15: gate on a latched confirmed invalid_grant BEFORE any file-content read or network call —
    // a dead token no longer gets re-POSTed every turn. The gate gives way the instant the file's
    // mtime changes (re-login), so a genuinely stale latch never outlives the credentials it named.
    private suspend fun doRefresh(): RefreshOutcome {
        // DR-59 (class law): logged-out is PROVEN by the stat, never an exists() pre-gate —
        // exists() reads false through an untraversable parent, so an operator with intact tokens
        // was told "not logged in". Only NoSuch with no NOFOLLOW entry is a genuine first
        // run/logout; everything else is ReadFailed, whose flatten line says NOT-logged-out.
        val statFailure = Cancellables.runCatchingCancellable {
            Files.getLastModifiedTime(authPath)
        }.exceptionOrNull()
        if (statFailure != null) {
            val genuinelyAbsent = statFailure is java.nio.file.NoSuchFileException &&
                !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            return if (genuinelyAbsent) RefreshOutcome.NoCredentialsFile else RefreshOutcome.ReadFailed(statFailure)
        }
        val identity = authFile.grokAuthIdentityOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(identity)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val priorAccess = authJson.cachedAccess()
        // AUTH-002: wire the daemon log sink so the lock's proceed-unlocked fallback is observable
        // (CredentialLock.withLock's `log` default is a silent no-op) instead of only greppable
        // in a source comment.
        val outcome = CredentialLock.withLock(authPath, log = log) {
            authFile.refreshLocked(
                priorAccess,
                PersistRotation { rt, tokens, access ->
                    persistRotation(rt, tokens, access)
                },
            )
        }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) {
            invalidGrantLatch.latch(identity)
        }
        return outcome
    }

    private fun persistRotation(refreshToken: String, fresh: GrokRefreshedTokens, access: String): RefreshOutcome {
        // SH-02(a): an endpoint response with no expires_in used to persist a NULL expiry, and the
        // merge then carried the stale on-disk value — the very next credentials() re-entered the
        // blocking tier and refreshed AGAIN, per request, burning rotating refresh tokens (the
        // 2026-07-18 credential-death shape). A just-minted token is not older than the one it
        // replaced: synthesize now+TTL (the SH-01 shared policy) so the expiry always advances.
        // Review finding: synthesizedExpiryMs(clock(), clock()) read the clock twice and clamped
        // the second read against the first, making the clamp a guaranteed no-op — there is no
        // real credential-file mtime here to clamp, only "now" itself, so write that directly.
        // DR-177: the same conversion the persist path uses, and for the same reason — a wrapped
        // expiry here reads as expired forever, which is a refresh per turn rather than one.
        val expiresAtMs = fresh.expiresIn?.let { CredentialExpiry.expiryFromNowMs(clock(), it) }
            ?: (clock() + SYNTHETIC_EXPIRY_TTL_MS)
        // SH-02(b): even with (a), a sub-floor grant (expires_in shorter than the stale floor)
        // leaves the next read inside the blocking tier — a SUCCESSFUL refresh that cannot satisfy
        // the tier logic. Back off instead of looping (CLIProxyAPI refreshIneffectiveBackoff),
        // log once per trip, and count it for the dashboard.
        if (expiresAtMs - clock() < STALE_FLOOR_MS) {
            lastIneffectiveRefreshAtMs = clock()
            ineffectiveRefreshes.incrementAndGet()
            log(
                "[$LOG_TAG] refresh succeeded but expiry did not advance past the stale floor — " +
                    "backing off ${REFRESH_INEFFECTIVE_BACKOFF_MS / MS_PER_S}s",
            )
        }
        // The endpoint already consumed the old refresh_token (Granted) by the time we get here — a
        // throwing write must degrade to a typed PersistFailed, never a raw throw through SingleFlight
        // out of credentials()/refresh(), so the not-yet-expired current token still gets served.
        Cancellables.runCatchingCancellable {
            writeSecure(
                authPath,
                authJson.mergedAuthJson(access, fresh.refreshToken ?: refreshToken, expiresAtMs).toString(),
            )
            // DR-151: the throwable travels WHOLE to the sink, which owns the render.
        }.getOrElse { return RefreshOutcome.PersistFailed.Write(it) }
        authJson.clearCache()
        return RefreshOutcome.Refreshed(Credentials.Bearer(access, null))
    }

    override suspend fun describe(): AuthDescription = authFile.describe()

    override fun credentialIdentity(): CredentialFileIdentity? = credentialEvidence().identity

    override fun credentialPresence(): CredentialPresence = credentialEvidence().presence

    override fun credentialEvidence(): CredentialEvidence =
        CredentialFileEvidenceReader.read(authPath)

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).

    private fun writeSecure(path: Path, content: String) { SecureFile.writeAtomic0600(path, content) }
}

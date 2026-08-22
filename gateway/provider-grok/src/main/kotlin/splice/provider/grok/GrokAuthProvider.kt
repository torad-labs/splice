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
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.core.util.WallClockIso
import splice.spi.CredentialLock
import splice.spi.LifecycleScope
import splice.spi.ProcessDispatchers
import splice.spi.SingleFlight
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val LOG_TAG = "grok-auth"

// SH-02(b): CLIProxyAPI's refreshIneffectiveBackoff value — long enough to stop a tight
// success/re-check loop, short enough that a genuinely recovering endpoint retries soon.
private const val REFRESH_INEFFECTIVE_BACKOFF_MS = 30_000L
private const val DEFAULT_CACHE_MS = 30_000L
private const val MS_PER_S = 1000L

/** Refresh this long before `expires` — well inside a 6h grok token, generous vs clock skew. */
private const val PROACTIVE_WINDOW_MS = 300_000L

/** Below this, block instead of prefetching: comfortably above the refreshCall's measured
 *  RTT (sub-second) and well below the 300s window, so most of the window stays non-blocking. */
private const val STALE_FLOOR_MS = 30_000L

public class GrokAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long = DEFAULT_CACHE_MS,
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
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

    // G15: the mtime probe lives on its own collaborator, not on this class. Measured, not assumed:
    // GrokAuthProvider holds 14 non-override functions and TooManyFunctions flags at 15 (overrides
    // are ignored), so folding grokAuthMtimeOrNull in here fails the build. HD-M5 red-proved this
    // with a synthetic 15th member; codex/kimi sit at 13 and DO host their own probe.
    private val authJson = GrokAuthJson(authPath, json, log, nowIso, clock, GrokAuthJson.SynthesizeExpiry { mtimeMs, nowMs -> synthesizeExpiry(mtimeMs, nowMs) })
    private fun synthesizeExpiry(mtimeMs: Long, nowMs: Long): Long = CredentialExpiry.synthesizedExpiryMs(mtimeMs, nowMs)
    private val authFile = GrokAuthFile(authPath, authJson, invalidGrantLatch, log, refreshCall)

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
    public val ineffectiveRefreshCount: Long get() = ineffectiveRefreshes.get()

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
            current
        } else {
            // stale floor: too close to hard expiry to risk it — block for a confirmed-fresh token,
            // same as pre-G17 behavior.
            val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }
            refreshed ?: current.takeIf { clock() < expiresAt }
        }
    }

    override suspend fun refresh(): Credentials? =
        singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }

    // Sealed per-mode outcome (discipline L3): a dead refresh token, a transport blip, and a
    // corrupt file are DIFFERENT stories; credentialsOrNull is the single logging flatten.
    // Staged (read → exchange → persist), each stage owning its own failure branches.
    // G1: capture what THIS process last served BEFORE the lock, then re-read authoritatively INSIDE
    // it — so a peer's rotation (landed while we waited on the lock) is seen, not overwritten.
    // G15: gate on a latched confirmed invalid_grant BEFORE any file-content read or network call —
    // a dead token no longer gets re-POSTed every turn. The gate gives way the instant the file's
    // mtime changes (re-login), so a genuinely stale latch never outlives the credentials it named.
    private suspend fun doRefresh(): RefreshOutcome {
        if (!Files.exists(authPath)) return RefreshOutcome.NoCredentialsFile
        val mtime = authFile.grokAuthMtimeOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(mtime)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val priorAccess = authJson.cachedAccess()
        // AUTH-002: wire the daemon log sink so the lock's proceed-unlocked fallback is observable
        // (CredentialLock.withLock's `log` default is a silent no-op) instead of only greppable
        // in a source comment.
        val outcome = CredentialLock.withLock(authPath, log = log) {
            authFile.refreshLocked(priorAccess, GrokAuthFile.PersistRotation { rt, tokens, access ->
                persistRotation(rt, tokens, access)
            })
        }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) {
            invalidGrantLatch.latch(mtime)
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
        val expiresAtMs = fresh.expiresIn?.let { clock() + it * MS_PER_S } ?: (clock() + SYNTHETIC_EXPIRY_TTL_MS)
        // SH-02(b): even with (a), a sub-floor grant (expires_in shorter than the stale floor)
        // leaves the next read inside the blocking tier — a SUCCESSFUL refresh that cannot satisfy
        // the tier logic. Back off instead of looping (CLIProxyAPI refreshIneffectiveBackoff),
        // log once per trip, and count it for the dashboard.
        if (expiresAtMs - clock() < STALE_FLOOR_MS) {
            lastIneffectiveRefreshAtMs = clock()
            ineffectiveRefreshes.incrementAndGet()
            log("[$LOG_TAG] refresh succeeded but expiry did not advance past the stale floor — " + "backing off ${REFRESH_INEFFECTIVE_BACKOFF_MS / MS_PER_S}s")
        }
        // The endpoint already consumed the old refresh_token (Granted) by the time we get here — a
        // throwing write must degrade to a typed PersistFailed, never a raw throw through SingleFlight
        // out of credentials()/refresh(), so the not-yet-expired current token still gets served.
        Cancellables.runCatchingCancellable {
            writeSecure(authPath, authJson.mergedAuthJson(access, fresh.refreshToken ?: refreshToken, expiresAtMs).toString())
        }.getOrElse { return RefreshOutcome.PersistFailed("auth.json write failed: $it") }
        authJson.clearCache()
        return RefreshOutcome.Refreshed(Credentials.Bearer(access, null))
    }

    override suspend fun describe(): AuthDescription = authFile.describe()

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).
    private fun writeSecure(path: Path, content: String) { SecureFile.writeAtomic0600(path, content) }
}

// PORT-OF: server/src/auth/codex-oauth.mjs runtime half @ pre-public-port-baseline — cached auth.json read
// (path+mtime+TTL), single-flight 401 refresh (grant_type=refresh_token, preserves other
// fields, 0600 write, cache invalidation), masked introspection. Implements the core
// RefreshableAuthProvider SPI. SEAM: token HTTP POST + clock injected for tests.
// Failure visibility (discipline L1): every auth-critical Result collapse consumes the failure
// with a stderr line first — a corrupt auth file must never masquerade as "not logged in".
// PROACTIVE refresh (grok-dead-head incident's latent codex twin, 2026-07-18): mirrors
// GrokAuthProvider's proactive-window shape, but codex's access_token is itself a JWT — the
// expiry comes from its own `exp` claim (decodeJwtClaims), not a stored `expires` field, so
// auth.json's shape stays byte-identical to the real codex CLI's. G17 two-tier as in grok/kimi:
// above the stale floor the request never waits on the refresh round trip.
package splice.provider.codex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialExpiry
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.auth.RefreshableAuthProvider
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

private const val LOG_TAG = "codex-auth"

// Refresh this long before the JWT `exp` claim — codex reference
// (CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES) uses 5 minutes; mirrors grok's PROACTIVE_WINDOW_MS.
private const val PROACTIVE_WINDOW_MS = 300_000L

// G17 stale floor: below this the refresh blocks the request (mirrors grok's STALE_FLOOR_MS).
private const val STALE_FLOOR_MS = 30_000L

public class CodexAuthProvider(
    private val authPath: Path,
    private val authCacheMs: Long,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val nowIso: WallClockIso = WallClockIso { Instant.ofEpochMilli(System.currentTimeMillis()).toString() },
    /** POST grant_type=refresh_token to the token URL; returns the classified attempt. */
    private val refreshCall: RefreshCall<RefreshedTokens>,
    // Owned background scope for the G17 async-prefetch tier, decoupled from any single request's
    // coroutine. Injectable so the daemon can tie it to its lifecycle and tests can drain it before
    // teardown (an in-flight prefetch racing @TempDir cleanup was a CI-only flake). Mirrors GrokAuthProvider.
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

    private val singleFlight = SingleFlight<Credentials?>()
    private val invalidGrantLatch = InvalidGrantLatch()

    // The JWT claim reader moved to CodexOAuth with the rest of the codex OAuth wire helpers
    // (HD-M5); this class reads `exp` and the account id through CodexAuthJson.
    private val authFile = CodexAuthFile()
    private val authJson = CodexAuthJson(nowIso, authPath, clock, SynthesizeExpiry { synthesizeExpiry(it) }, log)
    private val describeAuth = CodexAuthDescribe(authPath, authJson, authFile, invalidGrantLatch, log, refreshCall)

    init {
        // Lifecycle ownership: when prefetchScope ends (Daemon.stop cancels probeScope), cancel the
        // shared refresh so it cannot persist a token after shutdown. Per-request cancellation is
        // unaffected — it only cancels that caller's await, never the SingleFlight scope.
        prefetchScope.coroutineContext[Job]?.invokeOnCompletion { singleFlight.close() }
    }

    // SH-01: one shape-drift log per distinct auth.json mtime, not one per cached read.
    @Volatile
    private var synthLoggedMtime: Long = -1L

    /** SH-01: the shared missing-expiry policy, logged once per distinct mtime so a drifted
     *  auth.json shape (opaque token, exp-less JWT) is visible to the operator, not just felt. */
    private fun synthesizeExpiry(mtimeMs: Long): Long {
        if (synthLoggedMtime != mtimeMs) {
            synthLoggedMtime = mtimeMs
            log(
                "[codex-auth] access token carries no decodable exp — synthesized expiry " +
                    "mtime+4h (auth.json shape drifted?)",
            )
        }
        return CredentialExpiry.synthesizedExpiryMs(mtimeMs, clock())
    }

    // Three tiers by remaining time-to-expiry, same shape as GrokAuthProvider (G17): outside the
    // proactive window serve as-is; above the stale floor prefetch in the background and serve the
    // current token; below the floor block for a confirmed-fresh token exactly as before G17.
    override suspend fun credentials(): Credentials? {
        val snap = authJson.readSnapshot(authCacheMs) ?: return null
        val current = Credentials.Bearer(snap.access, snap.accountId)
        val expiresAt = snap.expiresAtMs
        return if (expiresAt == null || expiresAt - clock() >= PROACTIVE_WINDOW_MS) {
            current
        } else if (expiresAt - clock() >= STALE_FLOOR_MS) {
            // prefetch tier (G17): kick a single-flight refresh in the background, serve the CURRENT
            // token now. singleFlight still dedups concurrent entrants to one network call.
            prefetchScope.launch { singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) } }
            current
        } else {
            // stale floor: too close to hard expiry to risk it — block for a confirmed-fresh token.
            val refreshed = singleFlight.run { doRefresh().credentialsOrNull(LOG_TAG, log) }
            refreshed ?: (if (clock() < expiresAt) current else null)
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
        val mtime = authFile.codexAuthMtimeOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(mtime)) return RefreshOutcome.Rejected(INVALID_GRANT_REASON)
        val priorAccess = authJson.cachedAccess()
        // AUTH-002: wire the daemon log sink so the lock's proceed-unlocked fallback is observable
        // (CredentialLock.withLock's `log` default is a silent no-op) instead of only greppable
        // in a source comment.
        val outcome = CredentialLock.withLock(authPath, log = log) {
            describeAuth.refreshLocked(
                priorAccess,
                PersistRotation { raw, tokens, fresh, access ->
                    persistRotation(raw, tokens, fresh, access)
                },
            )
        }
        if (outcome is RefreshOutcome.Rejected && outcome.reason == INVALID_GRANT_REASON) {
            invalidGrantLatch.latch(mtime)
        }
        return outcome
    }

    private fun persistRotation(
        raw: JsonObject,
        tokens: JsonObject,
        fresh: RefreshedTokens,
        access: String,
    ): RefreshOutcome {
        // The endpoint already consumed the old refresh_token (Granted) by the time we get here — a
        // throwing write must degrade to a typed PersistFailed, never a raw throw through SingleFlight
        // out of credentials()/refresh(), so the not-yet-expired current token still gets served.
        Cancellables
            .runCatchingCancellable {
                writeSecure(authPath, authJson.mergedAuthJson(raw, tokens, fresh, access).toString())
            }
            // DR-151: the throwable travels WHOLE to the sink, which owns the render. No string
            // is built here, so no call site can pre-render one raw.
            .getOrElse { return RefreshOutcome.PersistFailed.Write(it) }
        authJson.clearCache()
        return authJson.readSnapshot(authCacheMs)
            ?.let { RefreshOutcome.Refreshed(Credentials.Bearer(it.access, it.accountId)) }
            ?: RefreshOutcome.PersistFailed.UnreadableAfterWrite
    }

    override suspend fun describe(): AuthDescription = describeAuth.describe()

    // Atomic 0600 credential write — routes to the shared primitive (was an inline temp→chmod→move).

    private fun writeSecure(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }
}

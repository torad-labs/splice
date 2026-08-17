// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: public constructor and every public
// method are byte-identical (HD-24, 2026-08-17). Now a facade wiring UsageRingFile -> UsageRing ->
// RateLimitFile/RateLimitHeaders -> RateLimitStore, so Daemon (3 sites), HeadServer (2),
// TurnDriver (3), FileSources (2) and every construction site across gateway/provider-* tests see
// no change.
package splice.gateway.usage

import splice.core.usage.RateLimitState
import splice.core.util.CoalescedFlush
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

// Widened from private to internal (HD-24): UsageRing (a sibling file) needs the same window.
internal const val FIVE_HOURS_MS: Long = 5 * 60 * 60 * 1000

// Widened from private to internal (HD-24): RateLimitStore (a sibling file) schedules on the
// same 1s lane.
internal const val USAGE_FLUSH_DELAY_MS: Long = 1_000L

/** 5h output-token window + ratelimit header persistence — the HUD contract files. */
public class UsageStore(
    usageFile: Path,
    ratelimitFile: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    log: LogSink = LogSink(DaemonLog::write),
) {
    // Shared by flushRateLimit and persistSnapshot (review 2026-07-22): one Any() serializes both
    // lanes' disk writes across UsageRingFile and RateLimitStore. Splitting it into two locks
    // would be a logic change (out of scope here).
    private val writeLock = Any()
    private val ringFile = UsageRingFile(usageFile, writeLock, log)
    private val ring = UsageRing(ringFile, clock() - FIVE_HOURS_MS)
    private val rateLimitStore = RateLimitStore(RateLimitFile(ratelimitFile), RateLimitHeaders(clock), writeLock)
    private val writeScheduled = AtomicBoolean(false)

    // In-memory updates are immediate. Persistence is coalesced onto the bounded file-I/O lane,
    // minute-bucketed, serialized, and atomically replaced: completion bursts neither block turn
    // slots nor race older snapshots over newer ones.
    public fun appendOutputTokens(outputTokens: Long) {
        if (ring.appendOutputTokens(clock(), outputTokens)) {
            CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
        }
    }

    public fun persistRateLimit(header: HeaderLookup): Unit = rateLimitStore.persistRateLimit(header)

    public fun readState(): UsageState {
        val (entries, tokens) = ring.stats(clock() - FIVE_HOURS_MS)
        return UsageState(
            windowHours = 5,
            entries = entries,
            outputTokens5h = tokens,
            ratelimit = rateLimitStore.readRateLimit(),
        )
    }

    /** Force the newest in-memory snapshot to stable storage (head stop and deterministic tests). */
    public fun flushNow() {
        val (snapshot, version) = ring.trimmedSnapshot(clock() - FIVE_HOURS_MS)
        ringFile.persistSnapshot(snapshot, version)
        rateLimitStore.flushRateLimit()
    }

    public fun readRateLimit(): RateLimitState? = rateLimitStore.readRateLimit()

    private fun flushScheduled() {
        val (snapshot, version) = ring.snapshot()
        ringFile.persistSnapshot(snapshot, version)
        writeScheduled.set(false)
        if (ring.isDirtierThan(ringFile.persistedVersion)) {
            CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
        }
    }
}

// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: header parse -> latest-wins
// in-memory pending -> coalesced disk write, and the matching read path, moved verbatim onto its
// own collaborator (HD-24, 2026-08-17). USG-003 and the 2026-07-22 review comments travel intact.
package splice.gateway.usage

import splice.core.usage.RateLimitState
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.CoalescedFlush
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Ratelimit persistence: [HeaderLookup] in, a coalesced write to [file] and an in-memory-first
 *  read back out. [writeLock] is the SAME lock [UsageStore] hands to [UsageRingFile] — splitting
 *  it into two locks would be a logic change (review 2026-07-22). `internal`: [file] is the
 *  internal [RateLimitFile] type, and the only construction site is [UsageStore] in this module. */
internal class RateLimitStore(
    private val file: RateLimitFile,
    private val headers: RateLimitHeaders,
    private val writeLock: Any,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    // Latest-wins pending ratelimit payload; consumed by the coalesced lane, flushNow, or a read.
    private val pendingRateLimit = AtomicReference<PendingRateLimit?>(null)
    private val rlWriteScheduled = AtomicBoolean(false)

    // Guarded by [writeLock], like UsageRingFile.persistFailureLogged: one line per failure
    // STREAK, reset by the next successful flush — never one per 1s-lane tick (DR-127).
    private var flushFailureLogged = false

    /** Parses x-ratelimit-limit-tokens / -remaining-tokens / -reset-tokens; no-op without a limit. */
    // best-effort by design: header/write failures are swallowed; cancellation propagates.
    // Coalesced onto the same 1s lane as the usage ring: these headers arrive on EVERY successful
    // upstream round, and a per-round atomic rewrite of this tiny latest-wins file was pure churn
    // (review 2026-07-22). flushNow() forces the pending payload out synchronously; readRateLimit()
    // serves it straight from memory instead (review 2026-07-22 round 3).
    public fun persistRateLimit(header: HeaderLookup) {
        Cancellables.runCatchingCancellable {
            val candidate = headers.pendingFrom(header) ?: return
            // Parse once here (not in readRateLimit) — see PendingRateLimit. USG-003: two turns can
            // race persistRateLimit concurrently with headers from different rounds; keepFresher
            // keeps whichever snapshot is fresher instead of last-write-wins.
            keepFresher(candidate)
            CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, rlWriteScheduled) { flushRateLimit() }
        }
    }

    /** Write the newest pending ratelimit payload, if any — flag cleared BEFORE the payload is
     *  consumed so a concurrent persistRateLimit always lands in a (re)scheduled flush, and the
     *  payload is consumed INSIDE writeLock so two racing flushers (the 1s lane vs a synchronous
     *  flushNow) serialize consume+write as one unit — consuming outside the lock let a
     *  descheduled flusher commit an OLDER payload over a newer one (review 2026-07-22). Widened
     *  from private to internal (HD-24): UsageStore.flushNow calls it from a different file. */
    internal fun flushRateLimit() {
        rlWriteScheduled.set(false)
        Cancellables.runCatchingCancellable {
            synchronized(writeLock) {
                val pending = pendingRateLimit.getAndSet(null) ?: return@synchronized
                // DR-127: the write OUTCOME decides the payload's fate. getAndSet-then-write
                // discarded the NEWEST snapshot when the disk write threw — no log, no retention,
                // statusline/HUD stale until the next round carried headers. On failure the
                // payload goes back through the same freshness gate persistRateLimit uses (a
                // concurrent persist may have installed a newer one while we held this), and the
                // failure logs once per streak — the UsageRingFile.persistSnapshot idiom.
                val failure = Cancellables.runCatchingCancellable { file.write(pending.encoded) }.exceptionOrNull()
                if (failure == null) {
                    flushFailureLogged = false
                } else {
                    keepFresher(pending)
                    if (!flushFailureLogged) {
                        flushFailureLogged = true
                        log(
                            "[usage] ratelimit flush FAILED (${SafeFailureText.render(failure)}) — " +
                                "newest snapshot retained in memory until a later flush succeeds\n",
                        )
                    }
                }
            }
        }
    }

    /** Freshness-gated install: keep [candidate] unless the current pending is strictly fresher
     *  — accumulateAndGet is the atomic-max idiom UpstreamClient.rateLimitedUntilMs already uses
     *  for the same shape of problem (USG-003). Shared by the header path and the failed-flush
     *  restore (DR-127) so the two cannot drift; ties favor [candidate], matching the original
     *  persist-path behavior. The accumulator's second arg is typed PendingRateLimit? (the
     *  AtomicReference's nullable T) and no null-guard smart-casts it, so [candidate] — the same
     *  instance accumulateAndGet passes in, non-null by construction — is read directly instead
     *  of !!-asserting it. */
    private fun keepFresher(candidate: PendingRateLimit) {
        pendingRateLimit.accumulateAndGet(candidate) { current, _ ->
            if (current == null || (candidate.parsed.updatedAt ?: 0L) >= (current.parsed.updatedAt ?: 0L)) {
                candidate
            } else {
                current
            }
        }
    }

    // best-effort by design: a missing/corrupt ratelimit file reads as null; cancellation propagates.
    // The pending payload IS the newest state, byte-identical to what the flush would write, so a
    // non-null pending is served straight from memory — no flush, no drain, no file I/O, and (already
    // parsed once in persistRateLimit) no re-parse of the same JSON on every /statusline tick and
    // /api/usage poll (review 2026-07-22). The inline flush this replaces had re-added, on the read
    // path, the churn coalescing removed from the round path (review 2026-07-22 round 3).
    public fun readRateLimit(): RateLimitState? = Cancellables.runCatchingCancellable {
        val pending = pendingRateLimit.get()
        if (pending != null) {
            pending.parsed
        } else {
            // Settle the coalesced lane first so a read never lags a just-arrived header by the 1s window.
            AsyncFileIo.drain()
            file.read()?.let { headers.rateLimitStateFrom(it) }
        }
    }.getOrNull()
}

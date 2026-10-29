// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: header parse -> latest-wins
// in-memory pending -> coalesced disk write, and the matching read path, moved verbatim onto its
// own collaborator (HD-24, 2026-08-17). USG-003 and the 2026-07-22 review comments travel intact.
package splice.head.usage

import splice.core.usage.QuotaHeaderRead
import splice.core.usage.RateLimitState
import splice.core.util.Cancellables
import splice.core.util.CoalescedFlush
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Ratelimit persistence: [QuotaHeaderRead] in, a coalesced write to [file] and an in-memory-first
 *  read back out. [writeLock] is the SAME lock [UsageStore] hands to [UsageRingFile] — splitting
 *  it into two locks would be a logic change (review 2026-07-22). `internal`: [file] is the
 *  internal [RateLimitFile] type, and the only construction site is [UsageStore] in this module. */
internal class RateLimitStore(
    private val file: RateLimitFile,
    private val headers: RateLimitHeaders,
    private val writeLock: UsageWriteLock,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    // Latest-wins pending ratelimit payload; cleared by the flush that writes it (the coalesced lane or flushNow).
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
    public fun persistRateLimit(header: QuotaHeaderRead) {
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
     *  read so a concurrent persistRateLimit always lands in a (re)scheduled flush, and the
     *  payload is read and written INSIDE writeLock so two racing flushers (the 1s lane vs a
     *  synchronous flushNow) serialize as one unit — taking it outside the lock let a descheduled
     *  flusher commit an OLDER payload over a newer one (review 2026-07-22). Widened from private
     *  to internal (HD-24): UsageStore.flushNow calls it from a different file. */
    internal fun flushRateLimit() {
        rlWriteScheduled.set(false)
        Cancellables.runCatchingCancellable {
            synchronized(writeLock) {
                val pending = pendingRateLimit.get() ?: return@synchronized
                // The payload stays pending until its file is written: readRateLimit serves pending
                // first, so a read that lands mid-write still answers this round. Taking it out
                // before the write left that read neither, null on the first write and the round
                // before on every later one (pre-push gate, 2026-10-29). Only a successful write
                // clears it, and only if no fresher round replaced it meanwhile; that round's persist
                // has already scheduled the next flush. DR-127 holds as before: a failed write keeps
                // the newest snapshot in memory and logs once per streak, the
                // UsageRingFile.persistSnapshot idiom.
                val failure = Cancellables.runCatchingCancellable { file.write(pending.encoded) }.exceptionOrNull()
                if (failure == null) {
                    pendingRateLimit.compareAndSet(pending, null)
                    flushFailureLogged = false
                } else if (!flushFailureLogged) {
                    flushFailureLogged = true
                    log(
                        "[usage] ratelimit flush FAILED (${SafeFailureText.render(failure)}); " +
                            "newest snapshot retained in memory until a later flush succeeds\n",
                    )
                }
            }
        }
    }

    /** Freshness-gated install: keep [candidate] unless the current pending is strictly fresher
     *  — accumulateAndGet is the atomic-max idiom UpstreamClient.rateLimitedUntilMs already uses
     *  for the same shape of problem (USG-003). Ties favor [candidate], matching the original
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
    // An unreadable file is logged by RateLimitFile.read, which answers null for it; nothing here can throw.
    public fun readRateLimit(): RateLimitState? {
        val pending = pendingRateLimit.get()
        // A flush clears pending only after its file is written, so a null pending means the file
        // already holds the newest commit. Read it without waiting on the writer.
        return pending?.parsed ?: file.read()?.let { headers.rateLimitStateFrom(it) }
    }
}

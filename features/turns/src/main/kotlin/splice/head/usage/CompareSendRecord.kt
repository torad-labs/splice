// NEW: Oct 13, 2026 — the record one Compare models send leaves once it is past the budget, folded out of
// UpstreamPlaygroundProbe (ConcentrationLawTest's ratchet): the probe sends, this writes beside HeadCompareSends.
package splice.head.usage

import kotlinx.coroutines.CancellationException
import splice.core.perf.OutcomeTag
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.core.util.WallClock

/** One Compare send's Requests row and budget spend, written once: the first way out writes them, any later one is a
 *  no-op. Past admit() the provider may already be charging, so every way out of [around] leaves them: an answer
 *  writes them with its tokens ([write]), any failure but cancellation as a failed send, a cancelled handler (the
 *  caller left) as an abandoned one before the cancellation goes on, and an Error in the finally. The write is
 *  synchronous, so a cancelled coroutine still makes it. */
public class CompareSendRecord(
    private val sends: CompareSends?,
    private val model: String,
    private val account: String?,
    private val clock: WallClock,
) {
    private val startedAt = clock()
    private var written = false

    public fun write(outcome: String, usage: Usage?) {
        if (written) return
        written = true
        sends?.answered(CompareAnswer(model, account, outcome, clock() - startedAt, usage))
    }

    /** Runs [send]; a failure other than cancellation is recorded and handed to [failed] for the caller's answer. */
    public suspend inline fun <R> around(send: () -> R, failed: (Throwable) -> R): R {
        try {
            return Cancellables.runCatchingBestEffort { send() }.getOrElse {
                write(OutcomeTag.UPSTREAM_FAILED.wire, null)
                failed(it)
            }
        } catch (cancelled: CancellationException) {
            write(OutcomeTag.CLIENT_ABORT.wire, null)
            throw cancelled
        } finally {
            write(OutcomeTag.UPSTREAM_FAILED.wire, null)
        }
    }
}

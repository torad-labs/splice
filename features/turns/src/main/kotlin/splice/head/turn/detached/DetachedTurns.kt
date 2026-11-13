// NEW 2026-11-12 (#525): the turns a head drives off their client's call, so a retiring head can tell which to finish.
package splice.head.turn.detached

import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.head.turn.TurnDrive
import java.util.concurrent.ConcurrentHashMap

/**
 * A compaction, and every collect turn with a session, runs on the head's detached scope and not on its client's
 * call, so a client that hangs up does not end it ([DetachedCompaction]). A head stop cancels that scope's children
 * together. A retiring head did the same, which cut the very turns a retire exists to keep: on Nov 12, 2026 a restart
 * cancelled a compaction 77 seconds into its answer, and the client's retry took 402 seconds.
 */
internal class DetachedTurns {
    private val running = ConcurrentHashMap<Job, TurnDrive>()

    /** [job] drives [drive] off its call from here until it ends. */
    fun track(job: Job, drive: TurnDrive) {
        running[job] = drive
        job.invokeOnCompletion { running.remove(job) }
    }

    /** A retiring head ends the ones it will not finish. One whose client is gone has no stream left to finish for,
     *  and the retry its client sends reaches the next daemon. One whose upstream has not begun answering is cut as
     *  any such turn is ([splice.head.turn.TurnOneDrive.retireActive]). One that is answering a client still
     *  connected runs to its end. Answers how many it ended that were answering: the head had counted those among
     *  the turns it keeps. */
    fun retire(): Int = running.entries.count { (job, drive) ->
        val answering = drive.perf.hasMark(PerfKeys.FIRST_BYTE)
        val ends = drive.channel.detached.get() || !answering
        if (ends) job.cancel()
        ends && answering
    }
}

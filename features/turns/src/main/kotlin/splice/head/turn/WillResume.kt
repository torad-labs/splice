// NEW: whether splice would carry on THIS round if it failed now, for the live listing's "Won't resume" (the Stalled
// card). The decision belongs to each dialect's re-anchor rule; this only asks it, with the facts the turn has, and
// adds the one condition no dialect can see: the head has no re-anchor tier armed.
package splice.head.turn

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.LiveWatchdogBudget
import splice.upstream.LiveRound
import splice.upstream.ReanchorPolicy

/** [policy] is null for a head whose dialect has no rule (chat), which never resumes. [budget] is read at call time,
 *  because the re-anchor tier is a live knob. */
internal class WillResume(private val policy: ReanchorPolicy?, private val budget: LiveWatchdogBudget) {
    fun now(perf: TurnPerf): Boolean {
        val rule = policy ?: return false
        if (!budget().stallReanchor.isFinite()) return false
        val spent = perf.count(PerfKeys.REANCHORS).toInt()
        return rule.wouldContinue(LiveRound(spent, perf.wire.toolOpened, perf.wire.textWritten))
    }
}

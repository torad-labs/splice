// The retry table's limit, overload and credit rows (failure-handling slice 8).
package splice.upstream.retry.limits

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.Moment
import splice.core.failure.Outlast
import splice.upstream.retry.BURST_FIRST_WAIT_MS
import splice.upstream.retry.BURST_WAITS
import splice.upstream.retry.BURST_WAIT_CAP_MS
import splice.upstream.retry.LOADING_PATIENCE_MS
import splice.upstream.retry.LOADING_WAIT_MS
import splice.upstream.retry.MAX_HOLD_MS
import splice.upstream.retry.OVERLOAD_FIRST_WAIT_MS
import splice.upstream.retry.OVERLOAD_LAST_WAIT_MS
import splice.upstream.retry.OVERLOAD_WAITS
import splice.upstream.retry.OVERLOAD_WAIT_CAP_MS
import splice.upstream.retry.Probes
import splice.upstream.retry.RetryStep
import splice.upstream.retry.RetryTurn
import splice.upstream.retry.Spend
import splice.upstream.retry.THROTTLE_FIRST_WAIT_MS
import splice.upstream.retry.THROTTLE_WAITS
import splice.upstream.retry.THROTTLE_WAIT_CAP_MS

/** A limit, an overload and a spent account: waited, moved or held by what the provider stated. */
internal object RetryLimits {
    /** A burst: waited 2, 4, 8 and 15 s, and never less than [soonMs], a time stated inside the floor, which is
     *  waited out rather than held. When these waits are spent, the caller supplies the bounded ending or hold. */
    private fun bursts(turn: RetryTurn, soonMs: Long, after: RetryStep): RetryStep {
        val n = turn.ledger.count(Spend.BURST)
        val waitMs = maxOf(soonMs, minOf(BURST_FIRST_WAIT_MS shl n, BURST_WAIT_CAP_MS))
        return if (n < BURST_WAITS) turn.wait(waitMs) else turn.switchOr(after)
    }

    fun limited(turn: RetryTurn, cause: FailureCause.Provider.Limited): RetryStep {
        if (cause.isExhausted) return turn.switchOr(turn.end)
        val lift = cause.lift
        if (lift is Lift.Unstated && turn.at.moment == Moment.MID_CONTENT) return turn.end
        val stated = turn.pastFloor(lift)
        return when {
            lift is Lift.Unstated -> turn.switchOr(throttle(turn))
            stated != null -> stated(turn, stated)
            else -> bursts(turn, turn.insideFloor(lift) ?: 0L, RetryStep.Hold(null, Probes.UNSTATED))
        }
    }

    /** A shared short throttle gets a finite recovery interval even after every eligible login has been tried. */
    private fun throttle(turn: RetryTurn): RetryStep {
        val n = turn.ledger.count(Spend.THROTTLE)
        val waitMs = minOf(THROTTLE_FIRST_WAIT_MS shl n, THROTTLE_WAIT_CAP_MS)
        return if (n < THROTTLE_WAITS) turn.wait(waitMs) else turn.end
    }

    fun overloaded(turn: RetryTurn, cause: FailureCause.Provider.Overloaded): RetryStep {
        val stated = turn.pastFloor(cause.lift)
        if (stated != null) return stated(turn, stated)
        val n = turn.ledger.count(Spend.OVERLOAD)
        val liftMs = (cause.lift as? Lift.At)?.let(turn::waitFor) ?: 0L
        val doubled = minOf(OVERLOAD_FIRST_WAIT_MS shl n, OVERLOAD_WAIT_CAP_MS)
        val scheduled = if (n == OVERLOAD_WAITS - 1) OVERLOAD_LAST_WAIT_MS else doubled
        if (n < OVERLOAD_WAITS) return turn.wait(maxOf(liftMs, scheduled))
        val waited = turn.ledger.waited(Spend.OVERLOAD) + turn.ledger.waited(Spend.LOADING)
        val loading = cause.loading && waited + LOADING_WAIT_MS <= LOADING_PATIENCE_MS
        return if (loading) turn.wait(LOADING_WAIT_MS) else turn.end
    }

    /** A spent account: a stated reset takes the same plan as a limit's, and with none stated the turn moves to a
     *  free login or holds for credit (V4-73). */
    fun payment(turn: RetryTurn, lift: Lift): RetryStep {
        val stated = turn.pastFloor(lift)
        val soon = turn.insideFloor(lift)
        return when {
            stated != null -> stated(turn, stated)
            soon != null -> bursts(turn, soon, RetryStep.Hold(null, Probes.CREDIT))
            else -> turn.switchOr(RetryStep.Hold(null, Probes.CREDIT))
        }
    }

    /** A stated lift past the floor: a reset past a week moves the turn to a free login or ends it; a warm session
     *  waits it out on its own login; otherwise another login takes the turn, or it is held to the stated time. */
    private fun stated(turn: RetryTurn, lift: Lift.At): RetryStep {
        val waitMs = turn.waitFor(lift)
        return when {
            waitMs > MAX_HOLD_MS -> turn.switchOr(turn.outlasted(Outlast.TOO_FAR, lift.at))
            turn.facts.warmWaitLeftMs >= waitMs -> turn.wait(waitMs)
            else -> turn.switchOr(turn.hold(lift))
        }
    }
}

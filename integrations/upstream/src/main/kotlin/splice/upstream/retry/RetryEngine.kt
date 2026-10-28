// NEW: failure-handling slice 4. The one decision of every retry (final-design.md section 8): from the cause, where
// the turn stood and the turn's ledger, the next step. It replaces RetryPolicy's plans, the stream re-issue interlock,
// the re-anchor and empty-answer budgets, the fold's own count and RefreshRetry's loop of token exchanges, so no layer
// multiplies another's tries. Pure: no clock and no I/O; the executor records each step it takes in the ledger.
//
// detekt holds a function under ten branches and the leaves number more, so the table is read by whens that each name
// every leaf of their kind with no else, the way ProviderSentences words them.
package splice.upstream.retry

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Outlast
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.failure.Stall
import splice.core.failure.Tear
import splice.upstream.retry.limits.RetryLimits
import java.time.Instant

/** What the engine knows of a turn besides its ledger. [pooled]: the head has more than one login; [freeLogin]:
 *  another of them can take the turn. [warmWaitLeftMs]: what a warm session may still wait on its own login.
 *  [continuesReasoning]: the provider continues an answer that only reasoned (Muse). [canContinue]: the head's
 *  re-anchor rule can continue from what arrived at this position (PassthroughReanchorPolicy and its siblings).
 *  [wallLeftMs]: what is left of the lowest wall that applies, the session's, a compaction's timer or the non-stream
 *  bound. [earliestLift]: the first reset among the head's logins. [holdWallLeftMs]: usable wait time left before the
 *  session's wall or a compaction's timer, with its response margin reserved; a non-stream request instead has its
 *  short silent wait allowance. A hold that cannot fit is handed to the client. */
public data class TurnFacts(
    val pooled: Boolean,
    val freeLogin: Boolean,
    val warmWaitLeftMs: Long,
    val continuesReasoning: Boolean,
    val canContinue: Boolean,
    val deadlines: Deadlines,
    val earliestLift: Instant?,
) {
    /** The progress deadline and usable hold allowance are separate readings of the same turn's bounds. */
    public data class Deadlines(val wallLeftMs: Long, val holdWallLeftMs: Long = Long.MAX_VALUE)

    public val wallLeftMs: Long get() = deadlines.wallLeftMs
    public val holdWallLeftMs: Long get() = deadlines.holdWallLeftMs
}

public object RetryEngine {
    /** The step after [cause] met at [at]. Every turn ends within [MAX_ATTEMPTS_OUTSIDE_HOLDS] sends outside holds, a
     *  wait the turn's wall cannot hold ends it instead, and a hold ends within its seven days and three missed
     *  resets, or is handed to the client where the session's wall cannot hold it (Holds). */
    public fun next(
        cause: FailureCause,
        at: Position,
        ledger: RetryLedger,
        facts: TurnFacts,
        now: Instant,
    ): RetryStep {
        if (ledger.attempts >= MAX_ATTEMPTS_OUTSIDE_HOLDS) return RetryStep.End(cause)
        val turn = RetryTurn(cause, at, ledger, facts, now)
        return when (val step = Rows.plan(turn)) {
            is RetryStep.Again -> Holds.waitBounded(turn, step)
            is RetryStep.Hold -> Holds.bounded(turn, step)
            is RetryStep.SwitchLogin, is RetryStep.End, RetryStep.Keep -> step
        }
    }

    /** The budget [step] spends for [cause], given what [ledger] already spent. */
    internal fun spendOf(step: RetryStep, cause: FailureCause, ledger: RetryLedger): Spend {
        if (step !is RetryStep.Again) return Spend.NONE
        return when (step.how) {
            How.REANCHOR ->
                if (cause is FailureCause.Provider.Unrecognized) Spend.UNRECOGNIZED else Spend.STREAM_RECOVERY
            How.CONTINUE_REASONING -> Spend.CONTINUE_REASONING
            How.AMEND_REASONING -> Spend.AMEND
            How.FOLD_CONTINUE -> Spend.FOLD
            How.REFRESH_LOGIN -> Spend.NONE
            How.SAME -> sameSpend(step, cause, ledger)
        }
    }

    private fun sameSpend(step: RetryStep.Again, cause: FailureCause, ledger: RetryLedger): Spend = when (cause) {
        is FailureCause.Provider -> providerSpend(step, cause, ledger)
        is FailureCause.Path.Unreachable -> Spend.UNREACHABLE
        is FailureCause.Path.Torn, is FailureCause.Path.Stalled -> Spend.FRESH_REISSUE
        is FailureCause.Splice -> if (cause is FailureCause.Splice.Protocol) Spend.CODE_MODE else Spend.NONE
    }

    private fun providerSpend(step: RetryStep.Again, cause: FailureCause.Provider, ledger: RetryLedger): Spend =
        when (cause) {
            is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment ->
                if (step.afterMs > HOLD_FLOOR_MS) Spend.WARM else Spend.BURST
            is FailureCause.Provider.Overloaded ->
                if (ledger.count(Spend.OVERLOAD) < OVERLOAD_WAITS) Spend.OVERLOAD else Spend.LOADING
            is FailureCause.Provider.ServerError -> Spend.SERVER_RESEND
            is FailureCause.Provider.Empty -> Spend.EMPTY
            is FailureCause.Provider.Unrecognized -> Spend.UNRECOGNIZED
            is FailureCause.Provider.Forbidden, is FailureCause.Provider.AuthRejected, is FailureCause.Provider.TooLong,
            is FailureCause.Provider.Rejected, is FailureCause.Provider.ModelMissing, is FailureCause.Provider.Refused,
            is FailureCause.Provider.ToolTear, is FailureCause.Provider.ReasoningCut,
            -> Spend.NONE
        }
}

/** One question to the engine, and the moves every row of the table is made of. */
internal class RetryTurn(
    val cause: FailureCause,
    val at: Position,
    val ledger: RetryLedger,
    val facts: TurnFacts,
    val now: Instant,
) {
    val end: RetryStep = RetryStep.End(cause)

    fun again(how: How, afterMs: Long = 0L): RetryStep = RetryStep.Again(afterMs, how)

    fun wait(afterMs: Long): RetryStep = RetryStep.Again(afterMs, How.SAME)

    /** [how] while [spend] is under [budget], then the end. */
    fun once(spend: Spend, budget: Int, how: How): RetryStep =
        if (ledger.count(spend) < budget) again(how) else end

    /** A stated lift past the floor, or null for any other. */
    fun pastFloor(lift: Lift): Lift.At? = (lift as? Lift.At)?.takeIf { waitFor(it) > HOLD_FLOOR_MS }

    /** How long a lift stated inside the floor is away, never less than nothing, or null for any other lift. This
     *  one is waited out on the login the turn is on: it is too close to hold (HOLD_FLOOR_MS). */
    fun insideFloor(lift: Lift): Long? =
        (lift as? Lift.At)?.let { waitFor(it) }?.takeIf { it <= HOLD_FLOOR_MS }?.coerceAtLeast(0L)

    fun waitFor(lift: Lift.At): Long = lift.at.toEpochMilli() - now.toEpochMilli()

    /** Another login takes the turn where the head has one free and no content reached the client. */
    fun switchOr(otherwise: RetryStep): RetryStep {
        val free = facts.pooled && facts.freeLogin
        return if (free && at.moment != Moment.MID_CONTENT) RetryStep.SwitchLogin else otherwise
    }

    fun hold(lift: Lift.At): RetryStep = RetryStep.Hold(lift.at, if (lift.firm) Probes.FIRM else Probes.SOFT_LIFT)

    /** The end of a hold that did not, or cannot, go on: [why], with the reset it would have waited for. */
    fun outlasted(why: Outlast, reset: Instant?): RetryStep =
        RetryStep.End(FailureCause.Splice.HoldOutlasted(why, cause, reset))
}

/** The table's rows, by leaf. */
private object Rows {
    fun plan(turn: RetryTurn): RetryStep = when (val cause = turn.cause) {
        is FailureCause.Provider -> provider(turn, cause)
        is FailureCause.Path -> path(turn, cause)
        is FailureCause.Splice ->
            if ((cause as? FailureCause.Splice.Protocol)?.fault == ProtocolFault.CODE_MODE_START) {
                turn.once(Spend.CODE_MODE, MAX_CODE_MODE_STARTS, How.SAME)
            } else {
                turn.end
            }
    }

    /** The leaves a wait or a login can change; the request's own verdicts go to [verdict]. */
    private fun provider(turn: RetryTurn, cause: FailureCause.Provider): RetryStep = when (cause) {
        is FailureCause.Provider.Limited -> RetryLimits.limited(turn, cause.lift, named = cause.window != null)
        is FailureCause.Provider.Overloaded -> RetryLimits.overloaded(turn, cause)
        is FailureCause.Provider.NeedsPayment -> RetryLimits.payment(turn, cause.lift)
        is FailureCause.Provider.ServerError -> Streams.server(turn)
        is FailureCause.Provider.Forbidden -> turn.switchOr(turn.end)
        is FailureCause.Provider.AuthRejected -> Logins.rejected(turn, cause)
        is FailureCause.Provider.Rejected, is FailureCause.Provider.Empty, is FailureCause.Provider.ReasoningCut,
        is FailureCause.Provider.Unrecognized, is FailureCause.Provider.TooLong, is FailureCause.Provider.ModelMissing,
        is FailureCause.Provider.Refused, is FailureCause.Provider.ToolTear,
        -> verdict(turn, cause)
    }

    private fun verdict(turn: RetryTurn, cause: FailureCause.Provider): RetryStep = when (cause) {
        is FailureCause.Provider.Rejected ->
            if (cause.staleReasoning) turn.once(Spend.AMEND, MAX_AMENDS, How.AMEND_REASONING) else turn.end
        is FailureCause.Provider.Empty -> Logins.empty(turn, cause.reasoningOnly)
        is FailureCause.Provider.ReasoningCut ->
            if (turn.ledger.count(Spend.FOLD) < MAX_FOLD_CONTINUES) turn.again(How.FOLD_CONTINUE) else RetryStep.Keep
        is FailureCause.Provider.Unrecognized -> Streams.unrecognized(turn)
        is FailureCause.Provider.TooLong, is FailureCause.Provider.ModelMissing, is FailureCause.Provider.Refused,
        is FailureCause.Provider.ToolTear,
        -> turn.end
        is FailureCause.Provider.Limited, is FailureCause.Provider.Overloaded, is FailureCause.Provider.NeedsPayment,
        is FailureCause.Provider.ServerError, is FailureCause.Provider.Forbidden, is FailureCause.Provider.AuthRejected,
        -> provider(turn, cause)
    }

    private fun path(turn: RetryTurn, cause: FailureCause.Path): RetryStep = when (cause) {
        is FailureCause.Path.Unreachable -> unreachable(turn)
        is FailureCause.Path.Torn -> torn(turn, cause)
        // A first byte that never came is sent fresh; a stream that went quiet mid-answer continues from what
        // arrived. The idle law decides that the path is dead before this cause exists at all.
        is FailureCause.Path.Stalled ->
            if (cause.tier == Stall.FIRST_BYTE) Streams.fresh(turn) else Streams.recovered(turn)
    }

    /** A path that did not answer: waited out while the waits last, about two minutes in all. */
    private fun unreachable(turn: RetryTurn): RetryStep {
        val n = turn.ledger.count(Spend.UNREACHABLE)
        val waitMs = minOf(UNREACHABLE_FIRST_WAIT_MS shl n, UNREACHABLE_WAIT_CAP_MS)
        return if (n < UNREACHABLE_WAITS) turn.wait(waitMs) else turn.end
    }

    /** A stream that broke: a swept-shut tool block and an event too large to read cannot be continued, a stream
     *  that carried no event is sent fresh, and anything else continues from what arrived. */
    private fun torn(turn: RetryTurn, cause: FailureCause.Path.Torn): RetryStep = when {
        cause.toolOpen || cause.why == Tear.OVERSIZED_EVENT -> turn.end
        cause.why == Tear.NO_EVENTS -> Streams.fresh(turn)
        else -> Streams.recovered(turn)
    }
}

/** A stream that broke, and a failure no table names. */
private object Streams {
    /** A server error: re-sent three times while the provider's stream never began, and continued from what
     *  arrived once it did. */
    fun server(turn: RetryTurn): RetryStep {
        if (turn.at.streamBegan) return recovered(turn)
        val n = turn.ledger.count(Spend.SERVER_RESEND)
        return if (n < SERVER_RESENDS) turn.wait(SERVER_FIRST_RESEND_MS shl n) else turn.end
    }

    /** A stream that broke: it continues from what arrived where it began and the head can continue it, on one
     *  budget shared by every recovery of the turn; where nothing arrived, the request is sent fresh. */
    fun recovered(turn: RetryTurn): RetryStep {
        if (!turn.at.streamBegan) return fresh(turn)
        val left = turn.ledger.count(Spend.STREAM_RECOVERY) < MAX_STREAM_RECOVERIES
        return if (turn.facts.canContinue && left) turn.again(How.REANCHOR) else turn.end
    }

    fun fresh(turn: RetryTurn): RetryStep = turn.once(Spend.FRESH_REISSUE, MAX_FRESH_REISSUES, How.SAME)

    /** Tried once more by where it stood: sent again before the stream began, continued after it where the head can. */
    fun unrecognized(turn: RetryTurn): RetryStep = when {
        turn.ledger.count(Spend.UNRECOGNIZED) >= MAX_UNRECOGNIZED_RETRIES -> turn.end
        !turn.at.streamBegan -> turn.again(How.SAME)
        turn.facts.canContinue -> turn.again(How.REANCHOR)
        else -> turn.end
    }
}

/** A login splice holds, and an answer that came back empty. */
private object Logins {
    /** A refused login: the client's own ends the turn at once, since splice never renews a login it only forwards
     *  (ClientAuthProvider), and one splice holds is renewed. */
    fun rejected(turn: RetryTurn, cause: FailureCause.Provider.AuthRejected): RetryStep =
        if (cause.owner == LoginOwner.CLIENT) turn.end else refused(turn, cause)

    /** Renewed once, its token exchange asked again 2 s and 4 s later while the token endpoint cannot answer, then
     *  another login, then the login's own ending. */
    private fun refused(turn: RetryTurn, cause: FailureCause.Provider): RetryStep {
        val ledger = turn.ledger
        if (!ledger.refreshStarted) return turn.again(How.REFRESH_LOGIN)
        val exchanges = ledger.exchangesForLogin
        if (ledger.lastExchangeForLogin == Exchange.UNAVAILABLE && exchanges < MAX_TOKEN_EXCHANGES) {
            return turn.again(How.REFRESH_LOGIN, EXCHANGE_FIRST_WAIT_MS shl (exchanges - 1))
        }
        return turn.switchOr(RetryStep.End(FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, cause.said)))
    }

    fun empty(turn: RetryTurn, reasoningOnly: Boolean): RetryStep =
        if (reasoningOnly && turn.facts.continuesReasoning) {
            turn.once(Spend.CONTINUE_REASONING, MAX_EMPTY_RETRIES, How.CONTINUE_REASONING)
        } else {
            turn.once(Spend.EMPTY, MAX_EMPTY_RETRIES, How.SAME)
        }
}

/** The bounds every hold keeps (final-design.md section 9): a cause that did not lift at three of the times it stated
 *  ends, as does a hold that reached its seven days, each with the held cause's family; a hold the session's wall
 *  cannot hold to its end, its reset and stagger or its first probe, is handed to the client with the reset (L). */
private object Holds {
    /** A collected request spends at most its short wait allowance. A limit it cannot wait out is handed to the
     *  client with the reset, rather than becoming an unqualified permanent refusal. */
    fun waitBounded(turn: RetryTurn, step: RetryStep.Again): RetryStep {
        val limited = turn.cause as? FailureCause.Provider.Limited
        return when {
            step.afterMs <= turn.facts.wallLeftMs -> step
            turn.facts.holdWallLeftMs > turn.facts.wallLeftMs -> turn.end
            limited == null -> turn.end
            else -> turn.outlasted(Outlast.WALL, (limited.lift as? Lift.At)?.at)
        }
    }

    fun bounded(turn: RetryTurn, hold: RetryStep.Hold): RetryStep {
        val ledger = turn.ledger
        val nowMs = turn.now.toEpochMilli()
        val since = ledger.heldSinceMs
        return when {
            ledger.missedResets >= MAX_MISSED_RESETS ->
                turn.outlasted(Outlast.MISSED_RESETS, hold.until)
            since != null && nowMs - since >= MAX_HOLD_MS -> turn.outlasted(Outlast.MAX_HOLD, hold.until)
            endsInMs(turn, hold) > turn.facts.holdWallLeftMs -> turn.outlasted(Outlast.WALL, hold.until)
            else -> hold
        }
    }

    /** How long from now the hold ends at the latest: its reset and the first stagger, or its first probe. */
    private fun endsInMs(turn: RetryTurn, hold: RetryStep.Hold): Long {
        val reset = hold.until?.let { it.toEpochMilli() - turn.now.toEpochMilli() + WAKE_STAGGER_MS }
        return reset ?: ProbeSchedule.firstMs(hold.probes)
    }
}

/** When a login's held turns are probed, by what the hold probes: the [n]th probe, counted from 0, comes this long
 *  after the one before it, the first after the hold began. */
public object ProbeSchedule {
    public fun gapMs(probes: Probes, n: Int): Long = when (probes) {
        Probes.FIRM -> FIRM_PROBE_MS
        Probes.CREDIT -> CREDIT_PROBE_MS
        Probes.SOFT_LIFT -> doubling(SOFT_LIFT_FIRST_PROBE_MS, SOFT_LIFT_LAST_DOUBLING_MS, n)
        Probes.UNSTATED -> doubling(UNSTATED_FIRST_PROBE_MS, UNSTATED_LAST_DOUBLING_MS, n)
        Probes.NONE -> Long.MAX_VALUE
    }

    internal fun firstMs(probes: Probes): Long = gapMs(probes, 0)

    /** [first] doubled [n] times while it stays within [last], then [PROBE_CEILING_MS]. */
    private fun doubling(first: Long, last: Long, n: Int): Long {
        val doubled = if (n < Long.SIZE_BITS - 2) first shl n else Long.MAX_VALUE
        return if (doubled in 1..last) doubled else PROBE_CEILING_MS
    }
}

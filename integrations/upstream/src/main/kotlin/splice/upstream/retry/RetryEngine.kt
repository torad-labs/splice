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
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.failure.Stall
import splice.core.failure.Tear
import java.time.Instant

/** What the engine knows of a turn besides its ledger. [pooled]: the head has more than one login; [freeLogin]:
 *  another of them can take the turn. [warmWaitLeftMs]: what a warm session may still wait on its own login.
 *  [continuesReasoning]: the provider continues an answer that only reasoned (Muse). [canContinue]: the head's
 *  re-anchor rule can continue from what arrived at this position (PassthroughReanchorPolicy and its siblings).
 *  [wallLeftMs]: what is left of the lowest wall that applies, the session's, a compaction's timer or the non-stream
 *  bound. [earliestLift]: the first reset among the head's logins, for slice 5's holds. */
public data class TurnFacts(
    val pooled: Boolean,
    val freeLogin: Boolean,
    val warmWaitLeftMs: Long,
    val continuesReasoning: Boolean,
    val canContinue: Boolean,
    val wallLeftMs: Long,
    val earliestLift: Instant?,
)

public object RetryEngine {
    /** The step after [cause] met at [at]. Every turn ends within [MAX_ATTEMPTS_OUTSIDE_HOLDS] sends, and a wait
     *  the turn's wall cannot hold ends it instead. */
    public fun next(
        cause: FailureCause,
        at: Position,
        ledger: RetryLedger,
        facts: TurnFacts,
        now: Instant,
    ): RetryStep {
        if (ledger.attempts >= MAX_ATTEMPTS_OUTSIDE_HOLDS) return RetryStep.End(cause)
        val step = Rows.plan(Turn(cause, at, ledger, facts, now))
        return if (step is RetryStep.Again && step.afterMs > facts.wallLeftMs) RetryStep.End(cause) else step
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
private class Turn(
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
}

/** The table's rows, by leaf. */
private object Rows {
    fun plan(turn: Turn): RetryStep = when (val cause = turn.cause) {
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
    private fun provider(turn: Turn, cause: FailureCause.Provider): RetryStep = when (cause) {
        is FailureCause.Provider.Limited -> Limits.limited(turn, cause.lift, named = cause.window != null)
        is FailureCause.Provider.Overloaded -> Limits.overloaded(turn, cause)
        is FailureCause.Provider.NeedsPayment -> Limits.payment(turn, cause.lift)
        is FailureCause.Provider.ServerError -> Streams.server(turn)
        is FailureCause.Provider.Forbidden -> turn.switchOr(turn.end)
        is FailureCause.Provider.AuthRejected -> Logins.rejected(turn, cause)
        is FailureCause.Provider.Rejected, is FailureCause.Provider.Empty, is FailureCause.Provider.ReasoningCut,
        is FailureCause.Provider.Unrecognized, is FailureCause.Provider.TooLong, is FailureCause.Provider.ModelMissing,
        is FailureCause.Provider.Refused, is FailureCause.Provider.ToolTear,
        -> verdict(turn, cause)
    }

    private fun verdict(turn: Turn, cause: FailureCause.Provider): RetryStep = when (cause) {
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

    private fun path(turn: Turn, cause: FailureCause.Path): RetryStep = when (cause) {
        is FailureCause.Path.Unreachable -> unreachable(turn)
        is FailureCause.Path.Torn -> torn(turn, cause)
        // A first byte that never came is sent fresh; a stream that went quiet mid-answer continues from what
        // arrived. The idle law decides that the path is dead before this cause exists at all.
        is FailureCause.Path.Stalled ->
            if (cause.tier == Stall.FIRST_BYTE) Streams.fresh(turn) else Streams.recovered(turn)
    }

    /** A path that did not answer: waited out while the waits last, about two minutes in all. */
    private fun unreachable(turn: Turn): RetryStep {
        val n = turn.ledger.count(Spend.UNREACHABLE)
        val waitMs = minOf(UNREACHABLE_FIRST_WAIT_MS shl n, UNREACHABLE_WAIT_CAP_MS)
        return if (n < UNREACHABLE_WAITS) turn.wait(waitMs) else turn.end
    }

    /** A stream that broke: a swept-shut tool block and an event too large to read cannot be continued, a stream
     *  that carried no event is sent fresh, and anything else continues from what arrived. */
    private fun torn(turn: Turn, cause: FailureCause.Path.Torn): RetryStep = when {
        cause.toolOpen || cause.why == Tear.OVERSIZED_EVENT -> turn.end
        cause.why == Tear.NO_EVENTS -> Streams.fresh(turn)
        else -> Streams.recovered(turn)
    }
}

/** A limit, an overload and a spent account: waited, moved or held by what the provider stated. */
private object Limits {
    /** A burst: waited 2, 4, 8 and 15 s, and never less than [soonMs], a time stated inside the floor, which is
     *  waited out rather than held. Then the turn moves to a free login, or holds with nothing stated to go on and
     *  [probes] to find the lift. */
    private fun bursts(turn: Turn, soonMs: Long, probes: Probes): RetryStep {
        val n = turn.ledger.count(Spend.BURST)
        val waitMs = maxOf(soonMs, minOf(BURST_FIRST_WAIT_MS shl n, BURST_WAIT_CAP_MS))
        return if (n < BURST_WAITS) turn.wait(waitMs) else turn.switchOr(RetryStep.Hold(null, probes))
    }

    fun limited(turn: Turn, lift: Lift, named: Boolean): RetryStep {
        val stated = turn.pastFloor(lift)
        return when {
            stated != null -> stated(turn, stated)
            // A named window with no time stated is long: Kimi's 5-hour 429 recovered on none of 62 retried turns.
            lift is Lift.Unstated && named -> turn.switchOr(RetryStep.Hold(null, Probes.UNSTATED))
            else -> bursts(turn, turn.insideFloor(lift) ?: 0L, Probes.UNSTATED)
        }
    }

    fun overloaded(turn: Turn, cause: FailureCause.Provider.Overloaded): RetryStep {
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
    fun payment(turn: Turn, lift: Lift): RetryStep {
        val stated = turn.pastFloor(lift)
        val soon = turn.insideFloor(lift)
        return when {
            stated != null -> stated(turn, stated)
            soon != null -> bursts(turn, soon, Probes.CREDIT)
            else -> turn.switchOr(RetryStep.Hold(null, Probes.CREDIT))
        }
    }

    /** A stated lift past the floor: a reset past a week moves the turn to a free login or ends it; a warm session
     *  waits it out on its own login; otherwise another login takes the turn, or it is held to the stated time. */
    private fun stated(turn: Turn, lift: Lift.At): RetryStep {
        val waitMs = turn.waitFor(lift)
        return when {
            waitMs > MAX_HOLD_MS -> turn.switchOr(turn.end)
            turn.facts.warmWaitLeftMs >= waitMs -> turn.wait(waitMs)
            else -> turn.switchOr(turn.hold(lift))
        }
    }
}

/** A stream that broke, and a failure no table names. */
private object Streams {
    /** A server error: re-sent three times while the provider's stream never began, and continued from what
     *  arrived once it did. */
    fun server(turn: Turn): RetryStep {
        if (turn.at.streamBegan) return recovered(turn)
        val n = turn.ledger.count(Spend.SERVER_RESEND)
        return if (n < SERVER_RESENDS) turn.wait(SERVER_FIRST_RESEND_MS shl n) else turn.end
    }

    /** A stream that broke: it continues from what arrived where it began and the head can continue it, on one
     *  budget shared by every recovery of the turn; where nothing arrived, the request is sent fresh. */
    fun recovered(turn: Turn): RetryStep {
        if (!turn.at.streamBegan) return fresh(turn)
        val left = turn.ledger.count(Spend.STREAM_RECOVERY) < MAX_STREAM_RECOVERIES
        return if (turn.facts.canContinue && left) turn.again(How.REANCHOR) else turn.end
    }

    fun fresh(turn: Turn): RetryStep = turn.once(Spend.FRESH_REISSUE, MAX_FRESH_REISSUES, How.SAME)

    /** Tried once more by where it stood: sent again before the stream began, continued after it where the head can. */
    fun unrecognized(turn: Turn): RetryStep = when {
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
    fun rejected(turn: Turn, cause: FailureCause.Provider.AuthRejected): RetryStep =
        if (cause.owner == LoginOwner.CLIENT) turn.end else refused(turn, cause)

    /** Renewed once, its token exchange asked again 2 s and 4 s later while the token endpoint cannot answer, then
     *  another login, then the login's own ending. */
    private fun refused(turn: Turn, cause: FailureCause.Provider): RetryStep {
        val ledger = turn.ledger
        if (!ledger.refreshStarted) return turn.again(How.REFRESH_LOGIN)
        val exchanges = ledger.exchangesForLogin
        if (ledger.lastExchangeForLogin == Exchange.UNAVAILABLE && exchanges < MAX_TOKEN_EXCHANGES) {
            return turn.again(How.REFRESH_LOGIN, EXCHANGE_FIRST_WAIT_MS shl (exchanges - 1))
        }
        return turn.switchOr(RetryStep.End(FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, cause.said)))
    }

    fun empty(turn: Turn, reasoningOnly: Boolean): RetryStep =
        if (reasoningOnly && turn.facts.continuesReasoning) {
            turn.once(Spend.CONTINUE_REASONING, MAX_EMPTY_RETRIES, How.CONTINUE_REASONING)
        } else {
            turn.once(Spend.EMPTY, MAX_EMPTY_RETRIES, How.SAME)
        }
}

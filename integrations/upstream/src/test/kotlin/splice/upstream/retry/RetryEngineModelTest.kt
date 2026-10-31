// NEW: failure-handling slice 4. RetryEngine against a small reference model of section 8's plan table, over generated
// sequences of failures of every kind, with random lifts, positions, pools, warm waits, walls and heads that can or
// cannot continue a cut answer. The model is this file's own reading of the table, written apart from the engine, and
// every expected value comes from the table, never from a run. RetryEngineThroughClientTest drives the same model
// through UpstreamClient over a MockEngine.
package splice.upstream.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Outlast
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.failure.Stall
import splice.core.failure.StoppedBy
import splice.core.failure.Tear
import splice.core.failure.Unreach
import java.time.Instant
import kotlin.random.Random
import kotlin.reflect.KClass

private val NOW: Instant = Instant.parse("2026-10-27T07:00:00Z")
private const val SEQUENCES = 4_000
private const val LONGEST = 40

/** One failure a sequence meets: what it was, where the turn stood, and what the turn knew. */
internal data class Met(val cause: FailureCause, val at: Position, val facts: TurnFacts)

/** One step of a run: what was met, the step the engine took and the step the model expects. */
internal data class Taken(val met: Met, val step: RetryStep, val expected: RetryStep, val sends: Int)

/** One row of the plan table: the step for a failure of its kind, given what the ledger spent. */
internal fun interface Row {
    fun step(met: Met, ledger: RetryLedger): RetryStep
}

/** Section 8's plan table, read apart from the engine: one row per kind, in the table's own numbers. */
internal object PlanModel {
    private const val FLOOR = 15_000L
    private const val WEEK = 604_800_000L

    fun next(met: Met, ledger: RetryLedger): RetryStep {
        if (ledger.attempts >= 24) return RetryStep.End(met.cause)
        val step = ROWS.getValue(met.cause::class).step(met, ledger)
        val fits = step !is RetryStep.Again || step.afterMs <= met.facts.wallLeftMs
        return if (fits) step else RetryStep.End(met.cause)
    }

    private fun end(met: Met): RetryStep = RetryStep.End(met.cause)

    private fun again(how: How, afterMs: Long = 0L): RetryStep = RetryStep.Again(afterMs, how)

    private fun waitMs(lift: Lift): Long? = (lift as? Lift.At)?.let { it.at.toEpochMilli() - NOW.toEpochMilli() }

    private fun switchOr(met: Met, otherwise: RetryStep): RetryStep {
        val free = met.facts.pooled && met.facts.freeLogin
        return if (free && met.at.moment != Moment.MID_CONTENT) RetryStep.SwitchLogin else otherwise
    }

    private fun hold(lift: Lift.At): RetryStep =
        RetryStep.Hold(lift.at, if (lift.firm) Probes.FIRM else Probes.SOFT_LIFT)

    private fun tooFar(met: Met, lift: Lift.At): RetryStep =
        RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.TOO_FAR, met.cause, lift.at))

    /** Over the floor: past a week another login takes it or it ends as a hold too far to wait, in its cause's family
     *  (slice 5), a warm session waits, else a login or a hold. */
    private fun stated(met: Met, lift: Lift.At, ms: Long): RetryStep = when {
        ms > WEEK -> switchOr(met, tooFar(met, lift))
        met.facts.warmWaitLeftMs >= ms -> again(How.SAME, ms)
        else -> switchOr(met, hold(lift))
    }

    /** The burst chain, shared by a limit and a spent account with no usable stated time: 2, 4, 8 and 15 s, never
     *  under a time stated inside the floor, then a free login or a hold with nothing stated. */
    private fun bursts(met: Met, ledger: RetryLedger, soonMs: Long, probes: Probes): RetryStep {
        val n = ledger.count(Spend.BURST)
        return if (n < 4) {
            again(How.SAME, maxOf(soonMs, listOf(2_000L, 4_000L, 8_000L, 15_000L)[n]))
        } else {
            switchOr(met, RetryStep.Hold(null, probes))
        }
    }

    private fun recover(met: Met, ledger: RetryLedger): RetryStep {
        val left = ledger.count(Spend.STREAM_RECOVERY) < 5
        return if (met.facts.canContinue && left) again(How.REANCHOR) else end(met)
    }

    private fun fresh(met: Met, ledger: RetryLedger): RetryStep =
        if (ledger.count(Spend.FRESH_REISSUE) < 2) again(How.SAME) else end(met)

    private val limited = Row { met, ledger ->
        val cause = met.cause as FailureCause.Provider.Limited
        val ms = waitMs(cause.lift)
        val lift = cause.lift
        when {
            lift is Lift.At && (ms ?: 0L) > FLOOR -> stated(met, lift, ms ?: 0L)
            lift is Lift.Unstated && cause.window != null -> switchOr(met, RetryStep.Hold(null, Probes.UNSTATED))
            else -> bursts(met, ledger, ms?.coerceAtLeast(0L) ?: 0L, Probes.UNSTATED)
        }
    }

    private val overloaded = Row { met, ledger ->
        val cause = met.cause as FailureCause.Provider.Overloaded
        val lift = cause.lift
        val ms = waitMs(lift) ?: 0L
        val n = ledger.count(Spend.OVERLOAD)
        val spent = ledger.waited(Spend.OVERLOAD) + ledger.waited(Spend.LOADING)
        when {
            lift is Lift.At && ms > FLOOR -> stated(met, lift, ms)
            n < 5 -> again(How.SAME, maxOf(ms, listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)[n]))
            cause.loading && spent + 60_000L <= 300_000L -> again(How.SAME, 60_000L)
            else -> end(met)
        }
    }

    private val server = Row { met, ledger ->
        val n = ledger.count(Spend.SERVER_RESEND)
        when {
            met.at.streamBegan -> recover(met, ledger)
            n < 3 -> again(How.SAME, listOf(200L, 400L, 800L)[n])
            else -> end(met)
        }
    }

    private val payment = Row { met, ledger ->
        val lift = (met.cause as FailureCause.Provider.NeedsPayment).lift
        val ms = waitMs(lift) ?: 0L
        when {
            lift is Lift.At && ms > FLOOR -> stated(met, lift, ms)
            // A stated reset takes a limit's plan, so one inside the floor is waited out in the burst chain.
            lift is Lift.At -> bursts(met, ledger, ms.coerceAtLeast(0L), Probes.CREDIT)
            else -> switchOr(met, RetryStep.Hold(null, Probes.CREDIT))
        }
    }

    private val login = Row { met, ledger ->
        val cause = met.cause as FailureCause.Provider.AuthRejected
        val n = ledger.exchangesForLogin
        val retried = ledger.lastExchangeForLogin == Exchange.UNAVAILABLE && n < 3
        when {
            cause.owner == LoginOwner.CLIENT -> end(met)
            !ledger.refreshStarted -> again(How.REFRESH_LOGIN)
            retried -> again(How.REFRESH_LOGIN, listOf(2_000L, 4_000L)[n - 1])
            else -> switchOr(met, RetryStep.End(FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, cause.said)))
        }
    }

    private val empty = Row { met, ledger ->
        val cause = met.cause as FailureCause.Provider.Empty
        val continues = cause.reasoningOnly && met.facts.continuesReasoning
        when {
            continues && ledger.count(Spend.CONTINUE_REASONING) < 1 -> again(How.CONTINUE_REASONING)
            !continues && ledger.count(Spend.EMPTY) < 1 -> again(How.SAME)
            else -> end(met)
        }
    }

    private val unrecognized = Row { met, ledger ->
        when {
            ledger.count(Spend.UNRECOGNIZED) >= 1 -> end(met)
            !met.at.streamBegan -> again(How.SAME)
            met.facts.canContinue -> again(How.REANCHOR)
            else -> end(met)
        }
    }

    private val unreachable = Row { met, ledger ->
        val n = ledger.count(Spend.UNREACHABLE)
        if (n < 8) again(How.SAME, listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L)[n] * 1_000L) else end(met)
    }

    private val torn = Row { met, ledger ->
        val cause = met.cause as FailureCause.Path.Torn
        when {
            cause.toolOpen || cause.why == Tear.OVERSIZED_EVENT -> end(met)
            cause.why == Tear.NO_EVENTS || !met.at.streamBegan -> fresh(met, ledger)
            else -> recover(met, ledger)
        }
    }

    private val stalled = Row { met, ledger ->
        val idle = (met.cause as FailureCause.Path.Stalled).tier == Stall.IDLE
        if (idle && met.at.streamBegan) recover(met, ledger) else fresh(met, ledger)
    }

    private val stale = Row { met, ledger ->
        val cause = met.cause as FailureCause.Provider.Rejected
        if (cause.staleReasoning && ledger.count(Spend.AMEND) < 1) again(How.AMEND_REASONING) else end(met)
    }

    private val fold = Row { _, ledger ->
        if (ledger.count(Spend.FOLD) < 3) again(How.FOLD_CONTINUE) else RetryStep.Keep
    }

    private val codeMode = Row { met, ledger ->
        val start = (met.cause as FailureCause.Splice.Protocol).fault == ProtocolFault.CODE_MODE_START
        if (start && ledger.count(Spend.CODE_MODE) < 1) again(How.SAME) else end(met)
    }

    private val ends = Row { met, _ -> end(met) }

    private val switchesOrEnds = Row { met, _ -> switchOr(met, end(met)) }

    private val ROWS: Map<KClass<out FailureCause>, Row> = mapOf(
        FailureCause.Provider.Limited::class to limited,
        FailureCause.Provider.Overloaded::class to overloaded,
        FailureCause.Provider.ServerError::class to server,
        FailureCause.Provider.NeedsPayment::class to payment,
        FailureCause.Provider.Forbidden::class to switchesOrEnds,
        FailureCause.Provider.AuthRejected::class to login,
        FailureCause.Provider.TooLong::class to ends,
        FailureCause.Provider.Rejected::class to stale,
        FailureCause.Provider.ModelMissing::class to ends,
        FailureCause.Provider.Refused::class to ends,
        FailureCause.Provider.Empty::class to empty,
        FailureCause.Provider.ToolTear::class to ends,
        FailureCause.Provider.ReasoningCut::class to fold,
        FailureCause.Provider.Unrecognized::class to unrecognized,
        FailureCause.Path.Unreachable::class to unreachable,
        FailureCause.Path.Torn::class to torn,
        FailureCause.Path.Stalled::class to stalled,
        FailureCause.Splice.Protocol::class to codeMode,
        FailureCause.Splice.CompactionUnfinished::class to ends,
        FailureCause.Splice.Declined::class to ends,
        FailureCause.Splice.Stopped::class to ends,
        FailureCause.Splice.TurnBudgetSpent::class to ends,
    )
}

/** The failures a generated sequence meets, every kind with its fields drawn at random. */
internal class Failures(private val random: Random) {
    private fun lift(): Lift = when (random.nextInt(5)) {
        0 -> Lift.Unstated
        1 -> Lift.At(NOW.plusMillis(random.nextLong(0, 15_001)), random.nextBoolean(), LiftSource.RETRY_AFTER)
        2 -> Lift.At(NOW.plusMillis(random.nextLong(15_001, 120_000)), random.nextBoolean(), LiftSource.BODY)
        3 -> Lift.At(NOW.plusSeconds(random.nextLong(3_600, 86_400 * 3)), firm = true, source = LiftSource.RESET_HEADER)
        else -> Lift.At(NOW.plusSeconds(86_400L * 8), firm = true, source = LiftSource.RESET_HEADER)
    }

    private fun <T> pick(values: List<T>): T = values[random.nextInt(values.size)]

    private val kinds: List<() -> FailureCause> = listOf(
        { FailureCause.Provider.Limited(lift(), pick(LimitWindow.entries + null)) },
        { FailureCause.Provider.Overloaded(lift(), loading = random.nextBoolean()) },
        { FailureCause.Provider.ServerError(pick(ServerFault.entries)) },
        { FailureCause.Provider.NeedsPayment(lift(), subscription = random.nextBoolean()) },
        { FailureCause.Provider.Forbidden() },
        { FailureCause.Provider.AuthRejected(pick(LoginOwner.entries)) },
        { FailureCause.Provider.TooLong(131_072L, whileAnswering = random.nextBoolean()) },
        { FailureCause.Provider.Rejected(staleReasoning = random.nextBoolean()) },
        { FailureCause.Provider.ModelMissing("gone") },
        { FailureCause.Provider.Refused(pick(RefusedBy.entries)) },
        { FailureCause.Provider.Empty(reasoningOnly = random.nextBoolean()) },
        { FailureCause.Provider.ToolTear(runaway = random.nextBoolean()) },
        { FailureCause.Provider.ReasoningCut(reasoningTokens = 518L * random.nextLong(1, 7) - 2) },
        { FailureCause.Provider.Unrecognized(clientError = random.nextBoolean()) },
        { FailureCause.Path.Unreachable(pick(Unreach.entries), local = random.nextBoolean()) },
        { FailureCause.Path.Torn(pick(Tear.entries), toolOpen = random.nextInt(4) == 0) },
        { FailureCause.Path.Stalled(pick(Stall.entries)) },
        { FailureCause.Splice.Protocol(pick(ProtocolFault.entries)) },
        { pick(SPLICE_ENDINGS) },
    )

    fun cause(): FailureCause = pick(kinds)()

    fun at(): Position {
        val moment = pick(Moment.entries.filter { it != Moment.ADMISSION })
        return Position(moment, streamBegan = moment == Moment.MID_CONTENT || random.nextBoolean())
    }

    fun facts(): TurnFacts {
        val pooled = random.nextBoolean()
        return TurnFacts(
            pooled = pooled,
            freeLogin = pooled && random.nextBoolean(),
            warmWaitLeftMs = pick(listOf(0L, 30_000L, 600_000L)),
            continuesReasoning = random.nextBoolean(),
            canContinue = random.nextBoolean(),
            deadlines = TurnFacts.Deadlines(pick(listOf(Long.MAX_VALUE, 3_600_000L, 20_000L, 1_000L))),
            earliestLift = null,
        )
    }

    /** What a token exchange answered when a step renewed the login. */
    fun exchange(): Exchange = pick(Exchange.entries)

    /** Mostly a sequence that keeps meeting the same kind, as a turn does, and at times another. */
    fun sequence(): List<Met> {
        val first = Met(cause(), at(), facts())
        return List(random.nextInt(1, LONGEST)) {
            if (random.nextInt(4) == 0) Met(cause(), at(), facts()) else first.copy(facts = facts())
        }
    }
}

private val SPLICE_ENDINGS: List<FailureCause> = listOf(
    FailureCause.Splice.CompactionUnfinished,
    FailureCause.Splice.Declined(Decline.Budget(limit = "$20.00", resetsAt = NOW)),
    FailureCause.Splice.Stopped(StoppedBy.CONSOLE),
    FailureCause.Splice.TurnBudgetSpent(budgetMs = 900_000L, beforeFirstAttempt = false),
)

/** Runs one sequence as an executor does: every failure but a renewal's own was a send; an Again sends again, a
 *  renewal makes one token exchange first, and any other step ends the run. */
internal fun run(sequence: List<Met>, random: Random): List<Taken> {
    val ledger = RetryLedger()
    val failures = Failures(random)
    val taken = mutableListOf<Taken>()
    ledger.sent()
    for (met in sequence) {
        val expected = PlanModel.next(met, ledger)
        val step = RetryEngine.next(met.cause, met.at, ledger, met.facts, NOW)
        taken += Taken(met, step, expected, ledger.attempts)
        if (step != expected || step !is RetryStep.Again) break
        ledger.took(step, met.cause)
        if (sends(step, failures, ledger)) ledger.sent()
    }
    return taken
}

/** Whether [step] sends the request now: a renewal's step makes one token exchange first, and the turn sends again
 *  only once the login is renewed, asking the engine again while it is not. */
private fun sends(step: RetryStep.Again, failures: Failures, ledger: RetryLedger): Boolean {
    if (step.how != How.REFRESH_LOGIN) return true
    val outcome = failures.exchange()
    ledger.exchanged(outcome)
    return outcome == Exchange.RENEWED
}

private fun runs(seed: Int = 26): List<List<Taken>> {
    val random = Random(seed)
    return List(SEQUENCES) { run(Failures(random).sequence(), random) }
}

/** The kinds whose plan is to end at once: the request's own verdict, which the same bytes earn again. */
private val PERMANENT_KINDS: Set<KClass<out FailureCause>> = setOf(
    FailureCause.Provider.TooLong::class,
    FailureCause.Provider.ModelMissing::class,
    FailureCause.Provider.Refused::class,
    FailureCause.Provider.Forbidden::class,
    FailureCause.Splice.Declined::class,
    FailureCause.Splice.Stopped::class,
)

private fun permanent(cause: FailureCause): Boolean =
    cause::class in PERMANENT_KINDS || refusedAsSent(cause) || protocolFault(cause)

/** The two leaves whose own field says whether a re-send can change them: reasoning the provider rejected can be
 *  left out and sent again, and the client's own login is never splice's to renew. */
private fun refusedAsSent(cause: FailureCause): Boolean =
    (cause as? FailureCause.Provider.Rejected)?.staleReasoning == false ||
        (cause as? FailureCause.Provider.AuthRejected)?.owner == LoginOwner.CLIENT

/** A protocol fault but the code-mode runtime's start, which is tried once more. */
private fun protocolFault(cause: FailureCause): Boolean =
    (cause as? FailureCause.Splice.Protocol)?.fault.let { it != null && it != ProtocolFault.CODE_MODE_START }

/** The budget a step of [run] spent, by the table's own reading of its kind: [overloads] is how many overload waits
 *  the run took before this step, since the table waits a still-loading model only once those five are spent. */
private fun spendOf(taken: Taken, overloads: Int = 0): Spend? {
    val step = taken.step as? RetryStep.Again ?: return null
    return when (step.how) {
        How.REANCHOR ->
            if (taken.met.cause is FailureCause.Provider.Unrecognized) Spend.UNRECOGNIZED else Spend.STREAM_RECOVERY
        How.FOLD_CONTINUE -> Spend.FOLD
        How.AMEND_REASONING -> Spend.AMEND
        How.CONTINUE_REASONING -> Spend.CONTINUE_REASONING
        How.REFRESH_LOGIN -> null
        How.SAME -> sameSpend(taken.met.cause, step.afterMs, overloads)
    }
}

/** What a plain re-send spent, by the row its cause is read on. A wait on a lift stated past the floor is a warm
 *  session's wait on its own login, which its own budget bounds and no count does. Past the five overload waits only
 *  a still-loading model's minute is a loading wait; any other wait there is one overload wait too many. */
private fun sameSpend(cause: FailureCause, afterMs: Long, overloads: Int): Spend? {
    val limit = (cause as? FailureCause.Provider.Limited)?.lift ?: (cause as? FailureCause.Provider.NeedsPayment)?.lift
    return when {
        limit != null -> if (pastFloor(limit)) null else Spend.BURST
        cause is FailureCause.Provider.Overloaded -> overloadSpend(cause, afterMs, overloads)
        else -> SAME_SPENDS[cause::class]
    }
}

private fun overloadSpend(cause: FailureCause.Provider.Overloaded, afterMs: Long, overloads: Int): Spend? = when {
    pastFloor(cause.lift) -> null
    overloads >= OVERLOAD_WAITS && cause.loading && afterMs == LOADING_WAIT_MS -> Spend.LOADING
    else -> Spend.OVERLOAD
}

/** The kinds whose plain re-send spends one count of its own; every other kind's re-send spends none. */
private val SAME_SPENDS: Map<KClass<out FailureCause>, Spend> = mapOf(
    FailureCause.Provider.ServerError::class to Spend.SERVER_RESEND,
    FailureCause.Provider.Empty::class to Spend.EMPTY,
    FailureCause.Provider.Unrecognized::class to Spend.UNRECOGNIZED,
    FailureCause.Path.Torn::class to Spend.FRESH_REISSUE,
    FailureCause.Path.Stalled::class to Spend.FRESH_REISSUE,
    FailureCause.Path.Unreachable::class to Spend.UNREACHABLE,
    FailureCause.Splice.Protocol::class to Spend.CODE_MODE,
)

/** A lift stated further away than the floor, which a warm session waits out on its own login. */
private fun pastFloor(lift: Lift): Boolean =
    (lift as? Lift.At)?.let { it.at.toEpochMilli() - NOW.toEpochMilli() > HOLD_FLOOR_MS } == true

/** Each step's spend in [run], in order, with the overload waits counted as the run takes them. */
private fun spends(run: List<Taken>): List<Pair<Spend, Long>> {
    var overloads = 0
    return run.mapNotNull { taken ->
        spendOf(taken, overloads)?.also { if (it == Spend.OVERLOAD) overloads += 1 }
            ?.let { it to ((taken.step as RetryStep.Again).afterMs) }
    }
}

class RetryEngineModelTest {
    private val runs = runs()
    private val steps = runs.flatten()

    @Test
    fun `the engine takes the reference model's action at every step`() {
        val misses = steps.filter { it.step != it.expected }
        assertEquals(emptyList<Taken>(), misses.take(5), "${misses.size} of ${steps.size} steps differ")
        // The sequences reach every way of sending again, so the comparison above covered all of them.
        val ways = steps.mapNotNull { (it.step as? RetryStep.Again)?.how }
        assertEquals(How.entries.toSet(), ways.toSet(), "every way of sending again was taken")
    }

    @Test
    fun `every family stays within its budget`() {
        val budgets = mapOf(
            Spend.STREAM_RECOVERY to MAX_STREAM_RECOVERIES,
            Spend.FOLD to MAX_FOLD_CONTINUES,
            Spend.AMEND to MAX_AMENDS,
            Spend.CONTINUE_REASONING to MAX_EMPTY_RETRIES,
            Spend.BURST to BURST_WAITS,
            Spend.OVERLOAD to OVERLOAD_WAITS,
            Spend.SERVER_RESEND to SERVER_RESENDS,
            Spend.FRESH_REISSUE to MAX_FRESH_REISSUES,
            Spend.EMPTY to MAX_EMPTY_RETRIES,
            Spend.UNRECOGNIZED to MAX_UNRECOGNIZED_RETRIES,
            Spend.CODE_MODE to MAX_CODE_MODE_STARTS,
            Spend.UNREACHABLE to UNREACHABLE_WAITS,
        )
        val reached = mutableSetOf<Spend>()
        runs.forEach { run ->
            val spent = spends(run)
            reached += spent.map { it.first }
            val counts = spent.groupingBy { it.first }.eachCount()
            budgets.forEach { (spend, budget) ->
                assertTrue((counts[spend] ?: 0) <= budget, "$spend over $budget: $run")
            }
            // A model still loading is asked every minute only while the overload waits and those minutes stay
            // inside five minutes in all.
            val loading = spent.filter { it.first == Spend.OVERLOAD || it.first == Spend.LOADING }.sumOf { it.second }
            val loadingWaits = counts[Spend.LOADING] ?: 0
            assertTrue(loadingWaits == 0 || loading <= LOADING_PATIENCE_MS, "loading waited ${loading}ms: $run")
        }
        assertEquals(budgets.keys + Spend.LOADING, reached, "the sequences spent every budget")
    }

    @Test
    fun `two heads of one API type take the same steps for the same replies`() {
        // Two heads read the same replies into the same causes, and each turn carries its own ledger: the engine
        // keeps nothing between turns, so the second head's turn takes the first one's steps, one for one, even
        // with the first head's turns run in between.
        val random = Random(41)
        val sequences = List(SEQUENCES / 4) { Failures(random).sequence() to random.nextInt() }
        val first = sequences.map { (sequence, seed) -> run(sequence, Random(seed)) }
        val second = sequences.map { (sequence, seed) -> run(sequence, Random(seed)) }
        first.zip(second).forEach { (one, other) ->
            assertEquals(one.map { it.step }, other.map { it.step }, "the same replies, the same steps")
        }
    }

    /** A lift this close is waited out on the login the turn is on, never made a hold's end: a hold costs the
     *  committed stream, and Claude Code abandons a request it waits on for half a minute (HOLD_FLOOR_MS). A hold
     *  with nothing stated is the other thing, and the burst chain ends in one by design (section 8's table). */
    @Test
    fun `no lift of 15 s or less is held`() {
        steps.mapNotNull { it.step as? RetryStep.Hold }.forEach { hold ->
            val until = hold.until ?: return@forEach
            assertTrue(until.toEpochMilli() - NOW.toEpochMilli() > HOLD_FLOOR_MS, "$hold")
        }
    }

    @Test
    fun `no permanent kind is re-sent`() {
        steps.filter { permanent(it.met.cause) }.forEach { taken ->
            assertTrue(taken.step !is RetryStep.Again, "$taken")
        }
    }

    @Test
    fun `REANCHOR is never taken where the head cannot continue`() {
        steps.filter { (it.step as? RetryStep.Again)?.how == How.REANCHOR }.forEach { taken ->
            assertTrue(taken.met.facts.canContinue && taken.met.at.streamBegan, "$taken")
        }
    }

    @Test
    fun `a login switches only before content`() {
        val switched = steps.filter { it.step == RetryStep.SwitchLogin }
        assertTrue(switched.isNotEmpty(), "the sequences reached a login switch")
        switched.forEach { taken ->
            assertTrue(taken.met.at.moment != Moment.MID_CONTENT && taken.met.facts.pooled, "$taken")
        }
    }

    @Test
    fun `fold continues and token exchanges count against their budgets`() {
        runs.forEach { run ->
            val folds = run.count { (it.step as? RetryStep.Again)?.how == How.FOLD_CONTINUE }
            assertTrue(folds <= MAX_FOLD_CONTINUES, "folds: $run")
            val exchanges = run.count { (it.step as? RetryStep.Again)?.how == How.REFRESH_LOGIN }
            assertTrue(exchanges <= MAX_TOKEN_EXCHANGES, "token exchanges: $run")
        }
        assertTrue(steps.any { it.step == RetryStep.Keep }, "a cut round reached its cap and its answer stood")
    }

    @Test
    fun `every sequence ends within 24 attempts outside holds`() {
        val long = Random(7).let { random ->
            List(SEQUENCES) {
                val facts = Failures(random).facts()
                val met = Met(
                    FailureCause.Path.Torn(Tear.RESET, toolOpen = false),
                    Position(Moment.MID_CONTENT, streamBegan = true),
                    facts.copy(canContinue = true, deadlines = facts.deadlines.copy(wallLeftMs = Long.MAX_VALUE)),
                )
                val mixed = Failures(random).sequence()
                    .map {
                        it.copy(
                            facts = it.facts.copy(deadlines = it.facts.deadlines.copy(wallLeftMs = Long.MAX_VALUE)),
                        )
                    }
                run(mixed + List(LONGEST) { met } + mixed, random)
            }
        }
        (runs + long).forEach { run ->
            assertTrue(run.last().sends <= MAX_ATTEMPTS_OUTSIDE_HOLDS, "${run.last().sends} sends: $run")
        }
    }

    @Test
    fun `a repeated R ending is P`() {
        val ended = steps.mapNotNull { taken -> (taken.step as? RetryStep.End)?.let { it.cause to taken.met.at } }
        assertTrue(ended.isNotEmpty(), "the sequences reached their endings")
        ended.forEach { (cause, at) ->
            assertTrue(!ClientWire.answer(cause, at, repeated = true).clientResends, "$cause at $at")
        }
    }
}

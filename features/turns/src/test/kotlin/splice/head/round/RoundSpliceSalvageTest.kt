// DR-124: the TERMINAL failed round's harvested usage is real billed burn exactly like the
// absorbed rounds' — ResponsesEventReducer deliberately harvests it from response.failed onto
// Failure.partial.usage "so the salvage accounting is real", and withFailureSalvage then dropped
// it: multi-round turns under-counted exactly their heaviest round, and a single-round failure
// with reported usage stamped nothing. These walls pin the fold under the cumulative round-usage
// law (input/cached are last-known, output/reasoning accrue).
package splice.head.round

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.turn.AbsorbedRounds
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageField

class RoundSpliceSalvageTest {

    private val rounds = RoundSplice()

    private fun failure(partialUsage: Usage?): TurnOutcome.Failure = TurnOutcome.Failure(
        message = "upstream died",
        cause = FailureCause.UPSTREAM_REPORTED,
        phase = FailurePhase.MID_OUTPUT,
        partial = partialUsage?.let { TurnOutcome.PartialRound(usage = it) },
    )

    @Test
    fun `terminal round's harvested usage folds onto absorbed rounds - DR-124`() {
        val acc = RoundUsage().plusRound(
            Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 20, reasoningTokens = 2),
        )
        val out = rounds.withFailureSalvage(
            failure(
                Usage(
                    inputTokens = 120,
                    outputTokens = 7,
                    reasoningTokens = 1,
                    reported = setOf(UsageField.INPUT, UsageField.OUTPUT),
                ),
            ),
            acc,
        ) as TurnOutcome.Failure
        // input/cached follow the cumulative law (terminal input wins; cached keeps last known),
        // output/reasoning accrue — the dying round's burn counts.
        assertEquals(
            Usage(
                inputTokens = 120,
                outputTokens = 10,
                reasoningTokens = 3,
                absorbed = AbsorbedRounds(rounds = 1, inputTokens = 100, cachedTokens = 20, outputTokens = 3),
                reported = setOf(UsageField.INPUT, UsageField.OUTPUT),
            ),
            out.salvagedUsage,
        )
    }

    /** A code-mode step that cut a streaming source round carries the count of it ([Usage.cutRounds]); a re-anchored
     *  turn folds that step with later rounds, and the count accrues instead of being superseded like the input. */
    @Test
    fun `source rounds a turn cut accrue across its folded rounds and its dying one`() {
        val acc = RoundUsage()
            .plusRound(Usage(inputTokens = 100, outputTokens = 3, cutRounds = 1))
            .plusRound(Usage(inputTokens = 120, outputTokens = 2, cutRounds = 2))
        assertEquals(3L, acc.toUsage().cutRounds)
        val out = rounds.withFailureSalvage(failure(Usage(outputTokens = 1, cutRounds = 1)), acc) as TurnOutcome.Failure
        assertEquals(4L, out.salvagedUsage.cutRounds)
    }

    @Test
    fun `single-round failure with reported usage stamps its own burn - DR-124`() {
        val out = rounds.withFailureSalvage(
            failure(Usage(inputTokens = 50, outputTokens = 7)),
            RoundUsage(),
        ) as TurnOutcome.Failure
        assertEquals(Usage(inputTokens = 50, outputTokens = 7), out.salvagedUsage)
    }

    @Test
    fun `terminal round with no input report keeps prior context outside its unknown bill - DR-124`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 55, outputTokens = 3))
        val out = rounds.withFailureSalvage(
            failure(Usage(outputTokens = 4, reported = setOf(UsageField.OUTPUT))),
            acc,
        ) as TurnOutcome.Failure
        assertEquals(0L, out.salvagedUsage.inputTokens)
        assertEquals(55L, out.salvagedUsage.clientContext?.inputTokens)
        assertEquals(55L, TurnBill.total(TurnBill.counters(out.salvagedUsage)).input)
        assertEquals(7L, out.salvagedUsage.outputTokens)
        assertNull(TurnBill.usd(TurnBill.counters(out.salvagedUsage), ModelRates(1.0, 0.1, 4.0)))
    }

    @Test
    fun `pure input burn on a single failed round is still accounted - DR-124`() {
        val out = rounds.withFailureSalvage(
            failure(Usage(inputTokens = 80)),
            RoundUsage(),
        ) as TurnOutcome.Failure
        assertEquals(Usage(inputTokens = 80), out.salvagedUsage)
    }

    @Test
    fun `no partial and no absorbed rounds leaves the failure untouched`() {
        val bare = failure(null)
        assertEquals(bare, rounds.withFailureSalvage(bare, RoundUsage()))
    }

    @Test
    fun `a reported terminal zero replaces input and bills the previous request once`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 55, outputTokens = 3, cachedTokens = 20))
        val out = rounds.withFailureSalvage(failure(Usage()), acc) as TurnOutcome.Failure
        assertEquals(0L, out.salvagedUsage.inputTokens)
        assertEquals(0L, out.salvagedUsage.cachedTokens)
        assertEquals(3L, out.salvagedUsage.outputTokens)
        assertEquals(
            AbsorbedRounds(rounds = 1, inputTokens = 55, cachedTokens = 20, outputTokens = 3),
            out.salvagedUsage.absorbed,
        )
    }

    @Test
    fun `a zero-only failure partial retains its observed zeros`() {
        val out = rounds.withFailureSalvage(failure(Usage()), RoundUsage()) as TurnOutcome.Failure
        assertEquals(Usage(), out.salvagedUsage)
    }

    @Test
    fun `an unreported terminal preserves the prior bill once while its own request stays unknown`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 55, outputTokens = 3))
        val out = rounds.withFailureSalvage(failure(Usage(reported = emptySet())), acc) as TurnOutcome.Failure
        assertEquals(0L, out.salvagedUsage.inputTokens)
        assertEquals(55L, out.salvagedUsage.clientContext?.inputTokens)
        assertEquals(55L, TurnBill.total(TurnBill.counters(out.salvagedUsage)).input)
        assertEquals(3L, out.salvagedUsage.outputTokens)
        assertNull(TurnBill.usd(TurnBill.counters(out.salvagedUsage), ModelRates(1.0, 0.1, 4.0)))
    }

    @Test
    fun `single-round provider salvage survives without a continuation partial`() {
        val known = Usage(inputTokens = 55, outputTokens = 3)
        val out = rounds.withFailureSalvage(failure(null).copy(salvagedUsage = known), RoundUsage())
        assertEquals(known, (out as TurnOutcome.Failure).salvagedUsage)
    }

    @Test
    fun `a content-only partial cannot hide the terminal provider salvage`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 50, outputTokens = 3))
        val terminal = failure(null).copy(
            partial = TurnOutcome.PartialRound(bodyText = "synthetic"),
            salvagedUsage = Usage(inputTokens = 80, outputTokens = 7),
        )
        val out = rounds.withFailureSalvage(terminal, acc) as TurnOutcome.Failure
        assertEquals(80L, out.salvagedUsage.inputTokens)
        assertEquals(10L, out.salvagedUsage.outputTokens)
        assertEquals(AbsorbedRounds(rounds = 1, inputTokens = 50, outputTokens = 3), out.salvagedUsage.absorbed)
    }

    // DR-125: a hang-up after absorbed rounds carries the accumulator (the abandoning round's own
    // stream died unparsed — there is no partial to fold in), and a clean abandonment stays bare.
    @Test
    fun `client abandonment carries the absorbed burn - DR-125`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 50, outputTokens = 6))
        val out = rounds.withFailureSalvage(TurnOutcome.ClientAbandoned(), acc) as TurnOutcome.ClientAbandoned
        assertEquals(50L, TurnBill.total(TurnBill.counters(out.salvagedUsage)).input)
        assertEquals(50L, out.salvagedUsage.clientContext?.inputTokens)
        assertEquals(6L, out.salvagedUsage.outputTokens)
        assertNull(TurnBill.usd(TurnBill.counters(out.salvagedUsage), ModelRates(1.0, 0.1, 4.0)))
    }

    @Test
    fun `client abandonment preserves intercepted burn alongside earlier gateway rounds`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 50, outputTokens = 6, reasoningTokens = 1))
        val intercepted = TurnOutcome.ClientAbandoned(
            salvagedUsage = Usage(inputTokens = 80, outputTokens = 7, cachedTokens = 20, reasoningTokens = 2),
        )
        val out = rounds.withFailureSalvage(intercepted, acc) as TurnOutcome.ClientAbandoned
        assertEquals(
            Usage(
                inputTokens = 80,
                outputTokens = 13,
                cachedTokens = 20,
                reasoningTokens = 3,
                absorbed = AbsorbedRounds(rounds = 1, inputTokens = 50, outputTokens = 6),
            ),
            out.salvagedUsage,
        )
    }

    @Test
    fun `a clean abandonment stays bare - DR-125`() {
        val bare = TurnOutcome.ClientAbandoned()
        assertEquals(bare, rounds.withFailureSalvage(bare, RoundUsage()))
    }
}

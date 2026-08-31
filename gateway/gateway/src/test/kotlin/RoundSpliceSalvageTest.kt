// DR-124: the TERMINAL failed round's harvested usage is real billed burn exactly like the
// absorbed rounds' — ResponsesEventReducer deliberately harvests it from response.failed onto
// Failure.partial.usage "so the salvage accounting is real", and withFailureSalvage then dropped
// it: multi-round turns under-counted exactly their heaviest round, and a single-round failure
// with reported usage stamped nothing. These walls pin the fold under the cumulative round-usage
// law (input/cached are last-known, output/reasoning accrue).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.round.RoundSplice
import splice.gateway.round.RoundUsage

class RoundSpliceSalvageTest {

    private val rounds = RoundSplice()

    private fun failure(partialUsage: Usage?): TurnOutcome.Failure = TurnOutcome.Failure(
        type = ErrorType.API_ERROR,
        message = "upstream died",
        partial = partialUsage?.let { TurnOutcome.PartialRound(usage = it) },
    )

    @Test
    fun `terminal round's harvested usage folds onto absorbed rounds - DR-124`() {
        val acc = RoundUsage().plusRound(
            Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 20, reasoningTokens = 2),
        )
        val out = rounds.withFailureSalvage(
            failure(Usage(inputTokens = 120, outputTokens = 7, reasoningTokens = 1)),
            acc,
        ) as TurnOutcome.Failure
        // input/cached follow the cumulative law (terminal input wins; cached keeps last known),
        // output/reasoning accrue — the dying round's burn counts.
        assertEquals(
            Usage(inputTokens = 120, outputTokens = 10, cachedTokens = 20, reasoningTokens = 3),
            out.salvagedUsage,
        )
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
    fun `terminal round reporting zero input keeps the last known input - DR-124`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 55, outputTokens = 3))
        val out = rounds.withFailureSalvage(
            failure(Usage(outputTokens = 4)),
            acc,
        ) as TurnOutcome.Failure
        assertEquals(Usage(inputTokens = 55, outputTokens = 7), out.salvagedUsage)
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

    // DR-125: a hang-up after absorbed rounds carries the accumulator (the abandoning round's own
    // stream died unparsed — there is no partial to fold in), and a clean abandonment stays bare.
    @Test
    fun `client abandonment carries the absorbed burn - DR-125`() {
        val acc = RoundUsage().plusRound(Usage(inputTokens = 50, outputTokens = 6))
        val out = rounds.withFailureSalvage(TurnOutcome.ClientAbandoned(), acc) as TurnOutcome.ClientAbandoned
        assertEquals(Usage(inputTokens = 50, outputTokens = 6), out.salvagedUsage)
    }

    @Test
    fun `a clean abandonment stays bare - DR-125`() {
        val bare = TurnOutcome.ClientAbandoned()
        assertEquals(bare, rounds.withFailureSalvage(bare, RoundUsage()))
    }
}

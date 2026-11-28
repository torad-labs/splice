package splice.upstream.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.Moment
import splice.core.failure.Outlast
import splice.core.failure.Position
import java.time.Instant

private val NOW: Instant = Instant.parse("2026-11-28T12:00:00Z")
private const val TWO_HOURS_SECONDS = 7_200L
private const val SHORT_BURST_SECONDS = 5L
private const val TIGHT_WALL_MS = 1_000L

class NoHoldRetryEngineTest {
    private val beforeCommit = Position(Moment.BEFORE_COMMIT, streamBegan = false)
    private val nonStream = Position(Moment.NON_STREAM, streamBegan = false)

    private fun facts(
        pooled: Boolean = false,
        freeLogin: Boolean = false,
        wallLeftMs: Long = Long.MAX_VALUE,
        holdWallLeftMs: Long = Long.MAX_VALUE,
        noHold: Boolean = true,
    ) = TurnFacts(
        pooled = pooled,
        freeLogin = freeLogin,
        warmWaitLeftMs = 0L,
        continuesReasoning = false,
        canContinue = false,
        deadlines = TurnFacts.Deadlines(wallLeftMs, holdWallLeftMs, noHold = noHold),
        earliestLift = null,
    )

    @Test
    fun `noHold ends immediately on stated limit past floor for both stream and non-stream`() {
        val reset = NOW.plusSeconds(TWO_HOURS_SECONDS)
        val limited = FailureCause.Provider.Limited(
            Lift.At(reset, firm = true, source = LiftSource.RESET_HEADER),
            LimitWindow.FIVE_HOUR,
        )

        val streamStep = RetryEngine.next(limited, beforeCommit, RetryLedger(), facts(noHold = true), NOW)
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, limited, reset)),
            streamStep,
        )

        val nonStreamStep = RetryEngine.next(
            limited,
            nonStream,
            RetryLedger(),
            facts(wallLeftMs = NON_STREAM_HOLD_MS, holdWallLeftMs = NON_STREAM_HOLD_MS, noHold = true),
            NOW,
        )
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, limited, reset)),
            nonStreamStep,
        )

        val defaultStream = RetryEngine.next(limited, beforeCommit, RetryLedger(), facts(noHold = false), NOW)
        assertTrue(defaultStream is RetryStep.Hold, "default holds: $defaultStream")
        val defaultNonStream = RetryEngine.next(
            limited,
            nonStream,
            RetryLedger(),
            facts(wallLeftMs = NON_STREAM_HOLD_MS, holdWallLeftMs = NON_STREAM_HOLD_MS, noHold = false),
            NOW,
        )
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)),
            defaultNonStream,
        )
    }

    @Test
    fun `noHold ends immediately on NeedsPayment and long stated Overloaded`() {
        val payment = FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false)
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, payment, null)),
            RetryEngine.next(payment, beforeCommit, RetryLedger(), facts(noHold = true), NOW),
        )

        val reset = NOW.plusSeconds(TWO_HOURS_SECONDS)
        val overloaded = FailureCause.Provider.Overloaded(
            Lift.At(reset, firm = false, source = LiftSource.RETRY_AFTER),
            loading = false,
        )
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, overloaded, reset)),
            RetryEngine.next(overloaded, beforeCommit, RetryLedger(), facts(noHold = true), NOW),
        )
    }

    @Test
    fun `noHold returns NO_HOLD instead of WALL when a short limit exceeds remaining wall`() {
        val reset = NOW.plusSeconds(SHORT_BURST_SECONDS)
        val limited = FailureCause.Provider.Limited(
            Lift.At(reset, firm = false, source = LiftSource.RETRY_AFTER),
            LimitWindow.FIVE_HOUR,
        )
        val tight = facts(wallLeftMs = TIGHT_WALL_MS, holdWallLeftMs = TIGHT_WALL_MS, noHold = true)
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, limited, reset)),
            RetryEngine.next(limited, nonStream, RetryLedger(), tight, NOW),
        )

        val defaultTight = facts(wallLeftMs = TIGHT_WALL_MS, holdWallLeftMs = TIGHT_WALL_MS, noHold = false)
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)),
            RetryEngine.next(limited, nonStream, RetryLedger(), defaultTight, NOW),
        )
    }

    @Test
    fun `noHold still switches to a free login and still waits short bursts within floor`() {
        val reset = NOW.plusSeconds(TWO_HOURS_SECONDS)
        val limited = FailureCause.Provider.Limited(
            Lift.At(reset, firm = true, source = LiftSource.RESET_HEADER),
            LimitWindow.FIVE_HOUR,
        )
        val pooledFree = facts(pooled = true, freeLogin = true, noHold = true)
        assertEquals(RetryStep.SwitchLogin, RetryEngine.next(limited, beforeCommit, RetryLedger(), pooledFree, NOW))

        val shortReset = NOW.plusSeconds(SHORT_BURST_SECONDS)
        val shortLimited = FailureCause.Provider.Limited(
            Lift.At(shortReset, firm = false, source = LiftSource.RETRY_AFTER),
            LimitWindow.FIVE_HOUR,
        )
        val ledger = RetryLedger()
        val burst = RetryEngine.next(shortLimited, beforeCommit, ledger, facts(noHold = true), NOW)
        assertEquals(RetryStep.Again(SHORT_BURST_SECONDS * 1_000L, How.SAME), burst)

        repeat(BURST_WAITS) {
            val step = RetryEngine.next(shortLimited, beforeCommit, ledger, facts(noHold = true), NOW)
            assertTrue(step is RetryStep.Again, "burst ${it + 1} waits: $step")
            ledger.took(step, shortLimited)
        }
        assertEquals(
            RetryStep.End(FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, shortLimited, null)),
            RetryEngine.next(shortLimited, beforeCommit, ledger, facts(noHold = true), NOW),
        )
    }
}

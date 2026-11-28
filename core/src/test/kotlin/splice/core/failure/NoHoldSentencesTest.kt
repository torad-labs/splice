package splice.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.client.NoHoldFlag
import splice.core.turn.ErrorType
import splice.core.util.LocalTimeText
import splice.core.wire.HttpStatus
import java.time.Instant
import java.time.ZoneId

private const val TWO_HOURS_SECONDS = 7_200L
private const val DISABLED = "Waiting for limits is disabled for this request."

class NoHoldSentencesTest {
    private val now = Instant.parse("2026-11-28T12:00:00Z")
    private val reset = now.plusSeconds(TWO_HOURS_SECONDS)
    private val lift = Lift.At(reset, firm = true, source = LiftSource.RESET_HEADER)
    private val lines = SpliceLines(LocalTimeText(ZoneId.of("UTC")))
    private val beforeCommit = Position(Moment.BEFORE_COMMIT, streamBegan = false)

    private fun facts(otherLogin: Boolean = false) = LineFacts(
        head = "claudex",
        loginCommand = "claudex login",
        providerName = "OpenAI",
        local = false,
        request = RequestFacts(compaction = false, firstExchange = false, otherLogin = otherLogin),
        now = now,
    )

    private fun render(cause: FailureCause.Splice.HoldOutlasted, otherLogin: Boolean = false): String {
        val answer = ClientWire.answer(cause, beforeCommit, otherLogin = otherLogin)
        assertEquals(HttpStatus.BAD_REQUEST, answer.status)
        assertEquals(ErrorType.INVALID_REQUEST, answer.type)
        assertEquals(false, answer.shouldRetry)
        assertFalse(answer.clientResends)
        return lines.ending(cause, beforeCommit, answer, facts(otherLogin)).text
    }

    @Test
    fun `NoHoldFlag accepts explicit truthy tokens and leaves defaults off`() {
        for (raw in listOf("1", "true", "TRUE", " yes ", "Yes")) {
            assertTrue(NoHoldFlag.isOn(raw), "expected true for '$raw'")
        }
        for (raw in listOf(null, "", "   ", "0", "false", "no", "off", "2")) {
            assertFalse(NoHoldFlag.isOn(raw), "expected false for '$raw'")
        }
    }

    @Test
    fun `Outlast NO_HOLD renders single-login, pooled and unreset limit lines and stops`() {
        val singleWindow = FailureCause.Splice.HoldOutlasted(
            Outlast.NO_HOLD,
            FailureCause.Provider.Limited(lift, LimitWindow.FIVE_HOUR),
            reset,
        )
        assertEquals(PersistedCause.VENDOR_RATE_LIMITED, singleWindow.persisted)
        val singleWindowText = render(singleWindow)
        assertTrue("claudex reached its 5-hour limit, which resets " in singleWindowText, singleWindowText)
        assertTrue(DISABLED in singleWindowText, singleWindowText)

        val singleNoWindowText = render(
            FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, FailureCause.Provider.Limited(lift, null), reset),
        )
        assertTrue("claudex is at its usage limit until " in singleNoWindowText, singleNoWindowText)
        assertTrue(DISABLED in singleNoWindowText, singleNoWindowText)

        val pooledWindowText = render(
            FailureCause.Splice.HoldOutlasted(
                Outlast.NO_HOLD,
                FailureCause.Provider.Limited(lift, LimitWindow.WEEKLY),
                reset,
            ),
            otherLogin = true,
        )
        assertTrue(
            "Every claudex login is at its usage limit; the first reached its weekly limit, until " in pooledWindowText,
            pooledWindowText,
        )
        assertTrue(DISABLED in pooledWindowText, pooledWindowText)

        val pooledNoWindowText = render(
            FailureCause.Splice.HoldOutlasted(Outlast.NO_HOLD, FailureCause.Provider.Limited(lift, null), reset),
            otherLogin = true,
        )
        assertTrue(
            "Every claudex login is at its usage limit, the first until " in pooledNoWindowText,
            pooledNoWindowText,
        )
        assertTrue(DISABLED in pooledNoWindowText, pooledNoWindowText)

        val noResetText = render(
            FailureCause.Splice.HoldOutlasted(
                Outlast.NO_HOLD,
                FailureCause.Provider.Limited(Lift.Unstated, null),
                null,
            ),
        )
        assertTrue("claudex reached its usage limit without saying when it resets" in noResetText, noResetText)
        assertTrue(DISABLED in noResetText, noResetText)
    }

    @Test
    fun `Outlast NO_HOLD renders payment and overloaded lines and stops`() {
        val payment = FailureCause.Splice.HoldOutlasted(
            Outlast.NO_HOLD,
            FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false),
            null,
        )
        assertEquals(PersistedCause.VENDOR_QUOTA_EXHAUSTED, payment.persisted)
        val paymentText = render(payment)
        assertTrue("Add credit with OpenAI, then send the message again." in paymentText, paymentText)

        val overloaded = FailureCause.Splice.HoldOutlasted(
            Outlast.NO_HOLD,
            FailureCause.Provider.Overloaded(lift, loading = false),
            reset,
        )
        assertEquals(PersistedCause.UPSTREAM_STATUS_5XX, overloaded.persisted)
        val overloadedText = render(overloaded)
        assertTrue("claudex is overloaded until " in overloadedText, overloadedText)
        assertTrue(DISABLED in overloadedText, overloadedText)
    }
}

// desktop-full (df-wire-answers): a provider's Retry-After on an overload reaches the client as whole seconds
// rounded up (WholeSeconds), and an overload without one emits no Retry-After.
package splice.head.hold

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Outlast
import splice.core.failure.Position
import splice.dialect.anthropic.failure.AnthropicReplies
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant

internal class OverloadRetryAfterTest {
    @Test
    fun `single-login handed-over weekly limit emits representative-claim seven_day with true unified-reset`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reset = now.plusSeconds(86_400L)
        val limited = FailureCause.Provider.Limited(Lift.At(reset, true, LiftSource.RESET_HEADER), LimitWindow.WEEKLY)
        val cause = FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)
        val answer = ClientWire.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false), otherLogin = false)

        assertEquals(
            mapOf(
                "retry-after" to "86400",
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-reset" to reset.epochSecond.toString(),
                "anthropic-ratelimit-unified-representative-claim" to "seven_day",
                "anthropic-ratelimit-unified-7d-reset" to reset.epochSecond.toString(),
            ),
            HandOverHeaders.of(answer, now.toEpochMilli()),
        )
    }

    @Test
    fun `single-login handed-over five-hour limit emits representative-claim five_hour with true unified-reset`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reset = now.plusSeconds(7_200L)
        val limited =
            FailureCause.Provider.Limited(Lift.At(reset, true, LiftSource.RESET_HEADER), LimitWindow.FIVE_HOUR)
        val cause = FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)
        val answer = ClientWire.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false), otherLogin = false)

        assertEquals(
            mapOf(
                "retry-after" to "7200",
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-reset" to reset.epochSecond.toString(),
                "anthropic-ratelimit-unified-representative-claim" to "five_hour",
                "anthropic-ratelimit-unified-5h-reset" to reset.epochSecond.toString(),
            ),
            HandOverHeaders.of(answer, now.toEpochMilli()),
        )
    }

    @Test
    fun `model-scoped weekly limit omits representative-claim instead of widening to seven_day`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reset = now.plusSeconds(86_400L)
        val limited =
            FailureCause.Provider.Limited(Lift.At(reset, true, LiftSource.RESET_HEADER), LimitWindow.MODEL_WEEKLY)
        val cause = FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)
        val answer = ClientWire.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false), otherLogin = false)

        assertEquals(
            mapOf(
                "retry-after" to "86400",
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-reset" to reset.epochSecond.toString(),
                "anthropic-ratelimit-unified-7d-reset" to reset.epochSecond.toString(),
            ),
            HandOverHeaders.of(answer, now.toEpochMilli()),
        )
    }

    @Test
    fun `pooled recheck handoff omits representative-claim while keeping 30s recheck and true window reset`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reset = now.plusSeconds(86_400L)
        val limited = FailureCause.Provider.Limited(Lift.At(reset, true, LiftSource.RESET_HEADER), LimitWindow.WEEKLY)
        val cause = FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)
        val answer = ClientWire.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false), otherLogin = true)

        assertEquals(
            mapOf(
                "retry-after" to "30",
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-reset" to (now.epochSecond + 30L).toString(),
                "anthropic-ratelimit-unified-7d-reset" to reset.epochSecond.toString(),
            ),
            HandOverHeaders.of(answer, now.toEpochMilli()),
        )
    }

    @Test
    fun `retry-after lift on weekly limit omits representative-claim whether firm or soft`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val retryAt = now.plusSeconds(3_600L)
        val expected = mapOf(
            "retry-after" to "3600",
            "anthropic-ratelimit-unified-status" to "rejected",
            "anthropic-ratelimit-unified-reset" to retryAt.epochSecond.toString(),
            "anthropic-ratelimit-unified-7d-reset" to retryAt.epochSecond.toString(),
        )

        listOf(false, true).forEach { firm ->
            val limited = FailureCause.Provider.Limited(
                Lift.At(retryAt, firm = firm, source = LiftSource.RETRY_AFTER),
                LimitWindow.WEEKLY,
            )
            val cause = FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, retryAt)
            val answer =
                ClientWire.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false), otherLogin = false)
            assertEquals(expected, HandOverHeaders.of(answer, now.toEpochMilli()), "firm=$firm")
        }
    }

    @Test
    fun `firm body reset on weekly limit emits representative-claim seven_day`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reset = now.plusSeconds(86_400L)
        val limited = FailureCause.Provider.Limited(Lift.At(reset, true, LiftSource.BODY), LimitWindow.WEEKLY)
        val cause = FailureCause.Splice.HoldOutlasted(Outlast.WALL, limited, reset)
        val answer = ClientWire.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false), otherLogin = false)

        assertEquals(
            mapOf(
                "retry-after" to "86400",
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-reset" to reset.epochSecond.toString(),
                "anthropic-ratelimit-unified-representative-claim" to "seven_day",
                "anthropic-ratelimit-unified-7d-reset" to reset.epochSecond.toString(),
            ),
            HandOverHeaders.of(answer, now.toEpochMilli()),
        )
    }

    @Test
    fun `overload with fractional Retry-After passes whole seconds rounded up`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reply = FailedReply(
            status = 529,
            body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            headers = mapOf("retry-after" to "6.2"),
            owner = LoginOwner.SPLICE,
            sent = SentKey { _, _ -> false },
            answeredAt = now,
        )
        val cause = AnthropicReplies.http(reply)
        val answer = ClientWire.answer(cause, Position(Moment.NON_STREAM, streamBegan = false))
        assertEquals(529, answer.status)
        assertEquals(null, answer.shouldRetry)
        assertEquals(mapOf("retry-after" to "7"), HandOverHeaders.of(answer, now.toEpochMilli()))
    }

    @Test
    fun `overload without Retry-After emits no Retry-After header`() {
        val cause = FailureCause.Provider.Overloaded(Lift.Unstated, loading = false)
        val answer = ClientWire.answer(cause, Position(Moment.NON_STREAM, streamBegan = false))
        assertEquals(emptyMap<String, String>(), HandOverHeaders.of(answer, 1_700_000_000_000L))
    }

    @Test
    fun `overload with retry-after-ms rounds up to whole seconds`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val lift = Lift.At(now.plusMillis(2_001L), firm = true, source = LiftSource.RETRY_AFTER)
        val cause = FailureCause.Provider.Overloaded(lift, loading = false)
        val answer = ClientWire.answer(cause, Position(Moment.NON_STREAM, streamBegan = false))
        assertEquals(mapOf("retry-after" to "3"), HandOverHeaders.of(answer, now.toEpochMilli()))
    }
}

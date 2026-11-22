// desktop-full (df-wire-answers): a provider's Retry-After on an overload reaches the client as whole seconds
// rounded up (WholeSeconds), and an overload without one emits no Retry-After.
package splice.head.hold

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.dialect.anthropic.failure.AnthropicReplies
import splice.upstream.failure.FailedReply
import java.time.Instant

internal class OverloadRetryAfterTest {
    @Test
    fun `overload with fractional Retry-After passes whole seconds rounded up`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val reply = FailedReply(
            status = 529,
            body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            headers = mapOf("retry-after" to listOf("6.2")),
            owner = LoginOwner.HEAD,
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

package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.core.perf.OutcomeTag
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.Family
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The provider's exhausted billing cycle ends a real turn, including another turn on the held credential. */
class TerminalLimitsThroughHeadsTest {
    @Test
    fun `monthly exhaustion ends first and cached turns then permits a new turn after the bounded cooldown`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { status(429); anthropicErrorBody("rate_limit_error", MONTHLY_WORDS) },
                reply { anthropicAnswer("quota is available again") },
            )
            val head = anthropic(replies = HeadReplies.kimi)

            assertTerminal(head.client.send(launched("first")))
            val recorded = HeadRecords(head).ended()
            assertEquals(OutcomeTag.RATE_LIMITED.wire, recorded.outcome)
            assertEquals("VENDOR_RATE_LIMITED", recorded.cause)
            assertTerminal(head.client.send(launched("second")))
            val collected = head.client.send(launched("collected").copy(stream = false))
            assertTerminal(collected)
            assertEquals(400, collected.status, collected.describe())
            assertEquals("false", collected.shouldRetry, collected.describe())
            assertEquals(1, provider.requests.size, "the held turn returns the stored words without hammering Kimi")
            assertEquals(Duration.ZERO, time.passed(), "neither turn waits or schedules a probe")
            assertTrue(head.heldTurns().isEmpty())

            time.advanceBy(20.seconds)
            val recovered = head.client.send(launched("after cooldown"))
            assertEquals("quota is available again", recovered.text, recovered.describe())
            assertTrue(recovered.completed, recovered.describe())
            assertEquals(listOf(Duration.ZERO, 20.seconds), provider.requests.map { it.at })
        }
    }

    @Test
    fun `monthly wording with a finite Retry-After refuses a held pooled credential without waiting`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    status(429)
                    header("Retry-After", "60")
                    anthropicErrorBody("rate_limit_error", MONTHLY_WORDS)
                },
                reply { anthropicAnswer("served after the bounded hold") },
            )
            val head = pooledAnthropic(listOf("primary"), replies = HeadReplies.kimi)

            assertTerminal(head.client.send(launched("first pooled")))
            assertTerminal(head.client.send(launched("second pooled")))
            assertEquals(1, provider.requests.size)
            assertEquals(Duration.ZERO, time.passed())
            assertTrue(head.heldTurns().isEmpty())

            time.advanceBy(60.seconds)
            val recovered = head.client.send(launched("pooled after cooldown"))
            assertEquals("served after the bounded hold", recovered.text, recovered.describe())
            assertTrue(recovered.completed, recovered.describe())
            assertEquals(2, provider.requests.size)
        }
    }

    @Test
    fun `monthly limit after streamed content preserves that content and closes as failure without a clean end`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    anthropicStart()
                    anthropicText("partial answer")
                    hold()
                    anthropicError("rate_limit_error", MONTHLY_WORDS)
                },
            )
            val head = anthropic(replies = HeadReplies.kimi)
            val stream = head.client.open(launched("streamed"))
            stream.awaitText("partial answer")
            provider.release()
            val answer = stream.finish()

            assertEquals("partial answer", answer.text, answer.describe())
            assertTrue(answer.contentBeforeError, answer.describe())
            assertEquals("rate_limit_error", answer.error?.type, answer.describe())
            assertTrue(MONTHLY_WORDS in answer.error?.message.orEmpty(), answer.describe())
            assertFalse(answer.completed, answer.describe())
            assertEquals(null, answer.stopReason, answer.describe())
            assertTrue(answer.notices.isEmpty(), answer.describe())
            assertEquals(1, provider.requests.size)
            assertEquals(Duration.ZERO, time.passed())
        }
    }

    private fun launched(text: String): ClientRequest =
        ClientRequest(text = text, headers = mapOf("x-splice-client-wall-ms" to "1200000"))

    private fun assertTerminal(answer: ClientAnswer) {
        assertTrue(Family.P in answer.families(), answer.describe())
        assertTrue(MONTHLY_WORDS in answer.error?.message.orEmpty(), answer.describe())
        assertTrue(answer.notices.isEmpty(), answer.describe())
        assertFalse(answer.completed, answer.describe())
        assertEquals(null, answer.stopReason, answer.describe())
    }
}

private const val MONTHLY_WORDS = "You've reached your monthly usage limit for this billing cycle"

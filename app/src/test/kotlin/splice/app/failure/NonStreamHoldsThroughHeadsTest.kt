package splice.app.failure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.head.scripted.ClientRequest
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.ScriptedReply
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Buffered requests may wait briefly for a limit without turning that allowance into a generation deadline. */
class NonStreamHoldsThroughHeadsTest {
    @Test
    fun `a non-stream request waits ten seconds silently and returns the answer`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(limited(START.plusSeconds(10)), reply { anthropicAnswer("after the short limit") })
            val head = anthropic(replies = HeadReplies.claude)

            val answer = head.client.send(ClientRequest(stream = false))

            assertEquals(200, answer.status, answer.describe())
            assertTrue("after the short limit" in answer.body, answer.describe())
            assertTrue(answer.events.isEmpty(), "a buffered wait sends no streaming notice")
            assertEquals(listOf(0.seconds, 10.seconds), provider.requests.map { it.at })
        }
    }

    @Test
    fun `a non-stream limit an hour away returns its true reset without scheduling a later send`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val reset = START.plusSeconds(1.hours.inWholeSeconds)
            provider.script(limited(reset), reply { anthropicAnswer("must not be sent") })
            val head = anthropic(replies = HeadReplies.claude)

            val answer = head.client.send(ClientRequest(stream = false))
            time.advanceBy(2.hours)
            withContext(Dispatchers.Default) { delay(25) }

            assertEquals(429, answer.status, answer.describe())
            assertEquals("3600", answer.header("retry-after"))
            assertEquals(reset.epochSecond.toString(), answer.header("anthropic-ratelimit-unified-reset"))
            assertEquals(reset.epochSecond.toString(), answer.header("anthropic-ratelimit-unified-5h-reset"))
            assertTrue(answer.error?.message?.startsWith("[splice] ") == true, answer.describe())
            assertEquals(1, provider.requests.size, "a returned buffered request owns no future send")
        }
    }

    @Test
    fun `an exhausted pooled non-stream turn uses the earliest login and gives the client a thirty second recheck`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val firstReset = START.plusSeconds(1.hours.inWholeSeconds)
            provider.script(
                limited(firstReset),
                limited(START.plusSeconds(2.hours.inWholeSeconds)),
                limited(firstReset),
            )
            val head = pooledAnthropic(listOf("first", "second"), replies = HeadReplies.claude)

            val answer = head.client.send(ClientRequest(stream = false))

            assertEquals(429, answer.status, answer.describe())
            assertEquals("30", answer.header("retry-after"))
            assertEquals((START.epochSecond + 30).toString(), answer.header("anthropic-ratelimit-unified-reset"))
            assertEquals(firstReset.epochSecond.toString(), answer.header("anthropic-ratelimit-unified-5h-reset"))
            assertEquals(
                listOf("first", "second", "first").map(ScriptedLogin::of),
                provider.requests.map { it.headers["authorization"].orEmpty().removePrefix("Bearer ") },
            )
        }
    }

    @Test
    fun `non-stream generation can remain silent beyond its fifteen second wait allowance`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { delay(20.seconds); anthropicAnswer("the generation finished") })
            val head = anthropic(replies = HeadReplies.claude)

            val answer = head.client.send(ClientRequest(stream = false))

            assertEquals(200, answer.status, answer.describe())
            assertTrue("the generation finished" in answer.body, answer.describe())
        }
    }

    @Test
    fun `a non-stream client that leaves during a short limit sends nothing at the reset`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(limited(START.plusSeconds(10)), reply { anthropicAnswer("must not be sent") })
            val head = anthropic(replies = HeadReplies.claude)
            val client = head.client.open(ClientRequest(stream = false))
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (provider.requests.isEmpty() || time.parkedNow() == 0) delay(5) }
            }

            client.hangUp()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (head.gate.snapshot().live.isNotEmpty()) delay(5) }
            }
            time.advanceBy(1.hours)
            withContext(Dispatchers.Default) { delay(50) }

            assertEquals(1, provider.requests.size, "the client close cancels the wait and every later send")
        }
    }

    @Test
    fun `a held login keeps its probe schedule when its oldest client leaves`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val spent = reply {
                status(429)
                anthropicErrorBody(
                    "rate_limit_error",
                    "You've reached your 5-hour usage limit. " +
                        "Your quota will reset when the current 5-hour window ends.",
                )
            }
            provider.script(spent, spent, reply { anthropicAnswer("the remaining turn continued") })
            val head = anthropic(replies = HeadReplies.kimi)
            val wall = mapOf("x-splice-client-wall-ms" to "606660000")
            val first = head.client.open(ClientRequest(headers = wall))
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (head.heldTurns().size != 1) delay(5) }
            }
            val second = head.client.open(ClientRequest(headers = wall))
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (head.heldTurns().size != 2) delay(5) }
            }

            first.hangUp()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (head.heldTurns().size != 1) delay(5) }
            }
            time.advanceBy(60.seconds)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (!second.finished) delay(5) }
            }

            assertEquals("the remaining turn continued", second.finish().text)
            assertEquals(3, provider.requests.size, "one scheduled probe serves the remaining held turn")
            assertEquals(60.seconds, provider.requests.last().at)
        }
    }

    private fun limited(reset: Instant): ScriptedReply = reply {
        status(429)
        header("anthropic-ratelimit-unified-status", "rejected")
        header("anthropic-ratelimit-unified-representative-claim", "five_hour")
        header("anthropic-ratelimit-unified-reset", reset.epochSecond.toString())
        anthropicErrorBody("rate_limit_error", "This request would exceed your account's rate limit.")
    }
}

private val START: Instant = Instant.parse("2026-10-26T12:00:00Z")

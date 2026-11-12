package splice.app.failure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.util.AsyncFileIo
import splice.head.perf.PerfStats
import splice.head.scripted.ClientRequest
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class HeldResetRestartTest {
    @Test
    fun `a head restart retains retry timing on disk and a served retry clears it`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val resumeAt = Instant.parse("2026-10-26T13:00:00Z")
            provider.script(
                reply {
                    status(429)
                    header(
                        "Retry-After",
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(resumeAt.atOffset(ZoneOffset.UTC)),
                    )
                    openAiErrorBody("rate_limit_error", "Rate limit reached for this plan.", "rate_limit_exceeded")
                },
                reply { chatAnswer("the retry was served") },
            )
            val head = chat()
            val id = "01000000-0000-7000-8000-000000000001"
            val request = ClientRequest(session = id, headers = mapOf("x-splice-client-wall-ms" to "606660000"))
            val stream = head.client.open(request)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (head.heldTurns().isEmpty()) delay(5) }
            }

            head.restart()
            val interrupted = stream.finish()
            assertFalse(interrupted.events.any { it.name == "message_stop" }, interrupted.describe())
            AsyncFileIo.drain()
            val restored = PerfStats(head.perfFile).endings.endingFor(id)
            assertEquals(OutcomeTag.RESTARTED.wire, restored?.outcome)
            assertEquals(resumeAt.epochSecond, restored?.resumeEpochSeconds)
            assertNull(restored?.resetEpochSeconds, "Retry-After alone does not state a spent-window reset")
            assertEquals(1, provider.requests.size, "a restart does not send the held request again")

            val answered = head.client.send(request)
            assertEquals("the retry was served", answered.text, answered.describe())
            assertTrue(answered.events.any { it.name == "message_stop" }, answered.describe())
            AsyncFileIo.drain()
            val after = PerfStats(head.perfFile).endings.endingFor(id)
            assertEquals(OutcomeTag.OK.wire, after?.outcome)
            assertNull(after?.resumeEpochSeconds, "a served retry clears its persisted retry horizon")
            assertNull(after?.resetEpochSeconds, "a served retry leaves no quota reset")
        }
    }
}

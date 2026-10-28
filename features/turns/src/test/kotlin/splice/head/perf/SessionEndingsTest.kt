// NEW: Oct 10, 2026 — each session's newest ending, as PerfStats notes it on append (Sessions' At limit).
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.util.AsyncFileIo
import java.nio.file.Path

class SessionEndingsTest {
    @TempDir lateinit var tmp: Path

    private var now = 1_000L
    private val stats by lazy { PerfStats(tmp.resolve("perf.jsonl"), clock = { now }) }

    private fun ended(
        session: String,
        outcome: String,
        account: String? = null,
        reset: Long? = null,
        model: String? = null,
    ) {
        val counters = reset?.let { mapOf(PerfKeys.EARLIEST_RESET_EPOCH_SECONDS to it) }.orEmpty()
        stats.record(
            PerfRowMeta(model, outcome, compact = false, session = session, account = PerfAccount(account)),
            PerfSnapshot(emptyMap(), counters),
        )
        now += 1_000
    }

    @Test
    fun `a request turned away at a spent plan names the session, its account and when the window comes back`() {
        ended("aaaa1111", "error:plan-limit", account = "Torad", reset = 1_791_700_000)

        val ending = stats.endings.endingFor("aaaa1111-0000-4000-8000-000000000001")

        assertEquals("error:plan-limit", ending?.outcome)
        assertEquals("Torad", ending?.account)
        assertEquals(1_791_700_000L, ending?.resetEpochSeconds)
        AsyncFileIo.drain()
    }

    @Test
    fun `the newest request is the one that counts, so a request that went through after the limit clears it`() {
        ended("aaaa1111", "error:plan-limit", reset = 1_791_700_000)
        ended("aaaa1111", "ok")

        assertEquals("ok", stats.endings.endingFor("aaaa1111-full-id")?.outcome)
        assertNull(stats.endings.endingFor("bbbb2222-full-id"), "a session with no row has no ending")
        AsyncFileIo.drain()
    }

    @Test
    fun `a request turned away before a model was chosen keeps the model splice last served the session`() {
        ended("aaaa1111", "ok", model = "claude-opus-5-5")
        ended("aaaa1111", "error:auth-missing")

        val ending = stats.endings.endingFor("aaaa1111-full-id")

        assertEquals("error:auth-missing", ending?.outcome)
        assertEquals("claude-opus-5-5", ending?.model)
        AsyncFileIo.drain()
    }

    @Test
    fun `a restarted daemon learns each session's served model from its row file`() {
        ended("aaaa1111", "ok", model = "claude-fable-5-1")
        AsyncFileIo.drain()

        val restarted = PerfStats(tmp.resolve("perf.jsonl"), clock = { now })

        assertEquals("claude-fable-5-1", restarted.endings.endingFor("aaaa1111-full-id")?.model)
    }

    @Test
    fun `a run of refusals is dated from its first refusal, through the client's own stops between re-sends`() {
        ended("aaaa1111", "ok")
        val first = now
        ended("aaaa1111", "error:upstream-failed")
        ended("aaaa1111", "client_abort")
        ended("aaaa1111", "error:upstream-failed")

        assertEquals(first, stats.endings.endingFor("aaaa1111-full-id")?.runSince)
        AsyncFileIo.drain()
    }

    @Test
    fun `a clean answer ends the run, and the next refusal opens a new one`() {
        ended("aaaa1111", "error:upstream-failed")
        ended("aaaa1111", "ok")
        assertNull(stats.endings.endingFor("aaaa1111-full-id")?.runSince, "an answer closes the run")

        val reopened = now
        ended("aaaa1111", "error:turn-cap")

        assertEquals(reopened, stats.endings.endingFor("aaaa1111-full-id")?.runSince)
        AsyncFileIo.drain()
    }

    @Test
    fun `a stop on its own opens no run`() {
        ended("aaaa1111", "client_abort")

        assertNull(stats.endings.endingFor("aaaa1111-full-id")?.runSince)
        AsyncFileIo.drain()
    }

    @Test
    fun `a restarted daemon dates the open run from the same first refusal`() {
        ended("aaaa1111", "ok")
        val first = now
        ended("aaaa1111", "error:upstream-failed")
        ended("aaaa1111", "error:upstream-failed")
        AsyncFileIo.drain()

        val restarted = PerfStats(tmp.resolve("perf.jsonl"), clock = { now })

        assertEquals(first, restarted.endings.endingFor("aaaa1111-full-id")?.runSince)
    }

    @Test
    fun `sessions sharing a display tag keep their own resets live and after restart`() {
        val first = "01a10000-0000-7000-8000-000000000001"
        val second = "01a10000-0000-7000-8000-000000000002"
        val heard = mutableListOf<String>()
        val watch = stats.endings.observe { id, _ -> heard += id }
        for ((id, reset) in listOf(first to 100L, second to 200L)) {
            stats.record(
                PerfRowMeta(
                    "test",
                    "error:plan-limit",
                    compact = false,
                    session = id.take(8),
                    transcript = PerfTranscriptIds(sessionId = id),
                ),
                PerfSnapshot(emptyMap(), mapOf(PerfKeys.EARLIEST_RESET_EPOCH_SECONDS to reset)),
            )
            now += 1_000
        }
        assertEquals(listOf(first, second), heard)
        assertEquals(100L, stats.endings.endingFor(first)?.resetEpochSeconds)
        assertEquals(200L, stats.endings.endingFor(second)?.resetEpochSeconds)
        AsyncFileIo.drain()
        val restarted = PerfStats(tmp.resolve("perf.jsonl"), clock = { now })
        assertEquals(100L, restarted.endings.endingFor(first)?.resetEpochSeconds)
        assertEquals(200L, restarted.endings.endingFor(second)?.resetEpochSeconds)
        watch.close()
    }
}

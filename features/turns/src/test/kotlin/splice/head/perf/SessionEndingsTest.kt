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

    private fun ended(session: String, outcome: String, account: String? = null, reset: Long? = null) {
        val counters = reset?.let { mapOf(PerfKeys.EARLIEST_RESET_EPOCH_SECONDS to it) }.orEmpty()
        stats.record(
            PerfRowMeta(null, outcome, compact = false, session = session, account = PerfAccount(account)),
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
}

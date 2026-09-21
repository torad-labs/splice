// NEW: V4-130 — the console's activity stores: per-UTC-day JSONL files, retention by whole days, the
// per-head switch on labels, and edges de-duplicated by tool_use id across a restart. Writes go
// through the async file lane, so each test waits for the rows it wrote before reading.
package splice.core.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val DAY_MS = 86_400_000L
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

class ActivityStoresTest {

    @TempDir
    lateinit var dir: Path

    /** Writes ride the single-threaded file lane, so draining it is an exact barrier: every row
     *  queued before this call is on disk after it. */
    private fun <T> await(read: () -> List<T>, count: Int): List<T> {
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        return read().also { assertEquals(count, it.size, it.toString()) }
    }

    @Test
    fun `edges are one per tool_use id, even when a restart records a call again`() {
        val stores = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE })
        stores.edges.record(MessageEdge("s-1", "uds:/run/a.sock", DAY_ONE, "toolu_1"))
        stores.edges.record(MessageEdge("s-1", "beta", DAY_ONE + 5, "toolu_2"))
        await({ stores.edges.edges() }, 2)
        val restarted = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE + 9 })
        restarted.edges.record(MessageEdge("s-1", "uds:/run/a.sock", DAY_ONE + 9, "toolu_1"))
        await({ Files.readAllLines(dir.resolve("edges-2026-09-18.jsonl")) }, 3)
        assertEquals(
            listOf(
                MessageEdge("s-1", "uds:/run/a.sock", DAY_ONE, "toolu_1"),
                MessageEdge("s-1", "beta", DAY_ONE + 5, "toolu_2"),
            ),
            restarted.edges.edges(),
            "the earliest observation of a call wins; the re-recorded one is not a second edge",
        )
    }

    @Test
    fun `labels and upstream rows are kept per session, only for the heads the switch names`() {
        val stores = ActivityStores(
            dir,
            retentionDays = 90,
            storeHeads = "claudex, codex",
            clock = WallClock { DAY_ONE },
        )
        stores.activity.label("s-1", "claudex", "Reading splice.toml", DAY_ONE)
        stores.activity.upstream("s-1", "codex", DAY_ONE + 1)
        stores.activity.label("s-1", "grok", "Running git status", DAY_ONE + 2)
        stores.activity.label("s-2", "claudex", "Editing Knob.kt", DAY_ONE + 3)
        await({ stores.activity.rows("s-2") }, 1)
        assertEquals(
            listOf(
                ActivityRow(DAY_ONE, "s-1", "claudex", "Reading splice.toml", upstream = false),
                ActivityRow(DAY_ONE + 1, "s-1", "codex", null, upstream = true),
            ),
            stores.activity.rows("s-1"),
            "grok is outside the switch, so its label was never written",
        )
    }

    @Test
    fun `the switch reads star as every head, empty as none, and a list as exactly those`() {
        assertTrue(ActivityHeads("*").stores("anything"))
        assertFalse(ActivityHeads("").stores("claudex"))
        assertTrue(ActivityHeads(" a ,b").stores("b"))
        assertFalse(ActivityHeads("a,b").stores("c"))
    }

    @Test
    fun `a day older than the window is neither read nor kept, and today counts as a day`() {
        var now = DAY_ONE
        val stores = ActivityStores(dir, retentionDays = 2, storeHeads = "*", clock = WallClock { now })
        stores.edges.record(MessageEdge("s-1", "a", now, "toolu_old"))
        await({ stores.edges.edges() }, 1)
        now = DAY_ONE + DAY_MS
        assertEquals(1, stores.edges.edges().size, "day one is still inside a two-day window on day two")
        now = DAY_ONE + 2 * DAY_MS
        assertEquals(emptyList<MessageEdge>(), stores.edges.edges(), "on day three day one is outside the window")
        stores.edges.record(MessageEdge("s-1", "a", now, "toolu_new"))
        await({ stores.edges.edges() }, 1)
        assertFalse(Files.exists(dir.resolve("edges-2026-09-18.jsonl")), "the first write of a new day sweeps")
        assertFalse(Files.exists(dir.resolve("edges-2026-09-18.jsonl.lock")), "and the day's lock sidecar with it")
        assertTrue(Files.exists(dir.resolve("edges-2026-09-20.jsonl")))
    }
}

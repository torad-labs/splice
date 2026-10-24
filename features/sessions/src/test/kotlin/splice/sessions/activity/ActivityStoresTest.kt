// NEW: V4-130 — the console's activity stores: per-UTC-day JSONL files, retention by whole days, the
// per-head switch on labels, and edges de-duplicated by tool_use id across a restart. Writes go
// through the async file lane, so each test waits for the rows it wrote before reading.
package splice.sessions.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.ActivityAction
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
    fun `a name's edge keeps the session that held it across a restart, and an address row stores none`() {
        val stores = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE })
        stores.edges.record(MessageEdge("s-1", "beta", DAY_ONE, "toolu_1", "s-2"))
        stores.edges.record(MessageEdge("s-1", "uds:/run/a.sock", DAY_ONE + 5, "toolu_2"))
        assertEquals(
            listOf(
                """{"from":"s-1","to":"beta","at":$DAY_ONE,"id":"toolu_1","to_session":"s-2"}""",
                """{"from":"s-1","to":"uds:/run/a.sock","at":${DAY_ONE + 5},"id":"toolu_2"}""",
            ),
            await({ Files.readAllLines(dir.resolve("edges-2026-09-18.jsonl")) }, 2),
            "the key is what a later read attributes by; an address row keeps its shape",
        )
        val restarted = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE + 9 })
        assertEquals(
            listOf(
                MessageEdge("s-1", "beta", DAY_ONE, "toolu_1", "s-2"),
                MessageEdge("s-1", "uds:/run/a.sock", DAY_ONE + 5, "toolu_2"),
            ),
            restarted.edges.edges(),
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
        stores.activity.label("s-1", "claudex", ActivityAction("Reading splice.toml"), DAY_ONE)
        stores.activity.upstream("s-1", "codex", DAY_ONE + 1)
        stores.activity.label("s-1", "grok", ActivityAction("Running git status"), DAY_ONE + 2)
        stores.activity.label("s-2", "claudex", ActivityAction("Editing Knob.kt"), DAY_ONE + 3)
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
    fun `a label keeps its tool and object, and a row written before they existed reads as neither`() {
        val stores = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE })
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("activity-2026-09-18.jsonl"),
            """{"at":$DAY_ONE,"session":"s-1","head":"claudex","label":"Reading splice.toml"}""" + "\n",
        )
        stores.activity.label("s-1", "claudex", ActivityAction("Running npm test", "Bash", "npm test"), DAY_ONE + 1)
        assertEquals(
            listOf(
                ActivityRow(DAY_ONE, "s-1", "claudex", "Reading splice.toml", upstream = false),
                ActivityRow(DAY_ONE + 1, "s-1", "claudex", "Running npm test", false, "Bash", "npm test"),
            ),
            await({ stores.activity.rows("s-1") }, 2),
            "the old sentence is not parsed back into a tool",
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

    @Test
    fun `a label is kept for today and yesterday, the UTC days a local day spans, and its edge keeps the knob's`() {
        var now = DAY_ONE
        val stores = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { now })
        stores.activity.label("s-1", "codex", ActivityAction("Running npm test"), now)
        stores.edges.record(MessageEdge("s-1", "uds:/run/a.sock", now, "toolu_1"))
        await({ stores.activity.rows("s-1") }, 1)
        now = DAY_ONE + DAY_MS
        assertEquals(1, stores.activity.rows("s-1").size, "yesterday's label is in a local day that began then")
        now = DAY_ONE + 2 * DAY_MS
        assertEquals(emptyList<ActivityRow>(), stores.activity.rows("s-1"), "the day before is in no local day today")
        assertEquals(1, stores.edges.edges().size, "an edge keeps the activityRetentionDays window")
        stores.activity.label("s-1", "codex", ActivityAction("Reading README.md"), now)
        await({ stores.activity.rows("s-1") }, 1)
        assertFalse(Files.exists(dir.resolve("activity-2026-09-18.jsonl")), "the first label two days on deletes it")
        assertFalse(Files.exists(dir.resolve("activity-2026-09-18.jsonl.lock")), "and its lock sidecar")
        assertTrue(Files.exists(dir.resolve("edges-2026-09-18.jsonl")), "the edges' day file stays")
    }

    @Test
    fun `the save on Your data cuts the edges at its moment, and spares a team that is still going`() {
        // Marlin, Oct 10, 2026: when a person says yes to a deletion, everything the history covers
        // is gone at that moment, and an edge is the record of who they messaged. The moment falls
        // INSIDE a UTC day on purpose: a whole-day delete would leave the morning's edges behind.
        val stores = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE })
        stores.edges.record(MessageEdge("s-1", "beta", DAY_ONE, "toolu_1"))
        stores.edges.record(MessageEdge("s-2", "gamma", DAY_ONE + 1_000, "toolu_2", "s-3"))
        stores.edges.record(MessageEdge("s-1", "beta", DAY_ONE + 3_000, "toolu_3"))
        await({ stores.edges.edges() }, 3)

        // s-2 is bound to a live team slot, so its edge stays even though it is before the moment.
        val gone = stores.edges.trimBefore(DAY_ONE + 2_000) { edge -> edge.from == "s-2" }

        assertEquals(1, gone, "only the unspared edge before the moment went")
        assertEquals(
            listOf(
                MessageEdge("s-2", "gamma", DAY_ONE + 1_000, "toolu_2", "s-3"),
                MessageEdge("s-1", "beta", DAY_ONE + 3_000, "toolu_3"),
            ),
            stores.edges.edges(),
            "the reader answers from what the cut left, with no restart",
        )
    }

    @Test
    fun `a cut that reaches every edge empties the store without a restart`() {
        val stores = ActivityStores(dir, retentionDays = 90, storeHeads = "*", clock = WallClock { DAY_ONE })
        stores.edges.record(MessageEdge("s-1", "beta", DAY_ONE - DAY_MS, "toolu_1"))
        stores.edges.record(MessageEdge("s-1", "beta", DAY_ONE, "toolu_2"))
        await({ stores.edges.edges() }, 2)

        assertEquals(2, stores.edges.trimBefore(DAY_ONE + 1))

        assertEquals(emptyList<MessageEdge>(), stores.edges.edges(), "the same store, reading what is left")
        assertFalse(Files.exists(dir.resolve("edges-2026-09-18.jsonl")), "the emptied day is deleted")
    }
}

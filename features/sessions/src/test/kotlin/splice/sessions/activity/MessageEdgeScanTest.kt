// NEW: V4-444 — a caller that names its edges reads a whole window the row cache cannot hold.
//
// On his desk (36,743 edges over 20 days) the row cache passed its 16 MiB allowance, so a team's edges, a session's
// edges and the all-sessions board answered the named storage refusal and showed no edges. These tests drive the store the
// way those routes do, over real day files, and hold it to three things:
//
//  · a caller that names few edges gets them from a window the row cache refuses;
//  · what it gets is what the row cache would have given for the same files (oldest first, one per id); and
//  · the allowance still refuses a caller that names more than it holds.
package splice.sessions.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val FIRST_DAY = 1_789_725_600_000L
private const val ONE_DAY = 86_400_000L
private const val FEW_SESSIONS = 40
private const val PER_DAY = 6_000

class MessageEdgeScanTest {
    @TempDir
    lateinit var dir: Path

    private var clock = FIRST_DAY

    private fun day(index: Int): Path {
        val date = java.time.Instant.ofEpochMilli(FIRST_DAY + index * ONE_DAY).toString().take(10)
        return dir.resolve("edges-$date.jsonl")
    }

    private fun store(maxBytes: Long = EDGE_CACHE_BYTES): MessageEdgeStore = MessageEdgeStore(
        ActivityDays(dir, EDGES_PREFIX, 7, WallClock { clock }),
        DayFiles(dir, EDGES_PREFIX),
        7,
        true,
        MessageEdgeCodec(),
        null,
        maxBytes,
    )

    private fun line(id: String, from: String, at: Long, toSession: String) =
        """{"from":"$from","to":"x","at":$at,"id":"$id","to_session":"$toSession"}""" + "\n"

    private fun writeDays(days: Int) {
        for (index in 0 until days) {
            clock = FIRST_DAY + index * ONE_DAY
            Files.writeString(
                day(index),
                (0 until PER_DAY).joinToString("") { n ->
                    val at = FIRST_DAY + index * ONE_DAY + n
                    line("d$index-$n", "s${n % FEW_SESSIONS}", at, "s${(n + 1) % FEW_SESSIONS}")
                },
            )
            Files.setLastModifiedTime(day(index), FileTime.fromMillis(FIRST_DAY + index * ONE_DAY + ONE_DAY - 1))
        }
    }

    @Test
    fun `a caller that names few edges reads a window the row cache refuses`() {
        assertTrue(AsyncFileIo.drain())
        writeDays(3)
        val allowance = 1_024L * 1_024L
        assertThrows(IOException::class.java) { store(allowance).edges() }
        val mine = store(allowance).scan(EdgeWanted { it.from == "s0" })
        assertEquals(PER_DAY * 3 / FEW_SESSIONS, mine.size)
        assertTrue(mine.all { it.from == "s0" })
        assertEquals(mine.map { it.at }.sorted(), mine.map { it.at }, "oldest first")
    }

    @Test
    fun `what it returns is what the row cache returns for the same files, and a repeated id keeps its first row`() {
        assertTrue(AsyncFileIo.drain())
        Files.writeString(
            day(0),
            line("a", "s1", FIRST_DAY + 1, "s2") + line("b", "s2", FIRST_DAY + 2, "s1") +
                line("a", "s1", FIRST_DAY + 9, "s2") + line("c", "s1", FIRST_DAY + 3, "s3"),
        )
        val store = store()
        val scanned = store.scan(EdgeWanted { it.from == "s1" })
        assertEquals(store.edges().filter { it.from == "s1" }, scanned)
        assertEquals(listOf("a", "c"), scanned.map { it.id })
        assertEquals(FIRST_DAY + 1, scanned.first().at)
    }

    @Test
    fun `a day written before since is not read`() {
        assertTrue(AsyncFileIo.drain())
        writeDays(3)
        val newest = store().scan(EdgeWanted { true }, FIRST_DAY + 2 * ONE_DAY)
        assertEquals(PER_DAY, newest.size)
        assertTrue(newest.all { it.id.startsWith("d2-") })
    }

    @Test
    fun `the allowance still refuses a caller that names more than it holds`() {
        assertTrue(AsyncFileIo.drain())
        writeDays(1)
        assertThrows(IOException::class.java) { store(2_048L).scan(EdgeWanted { true }) }
    }
}

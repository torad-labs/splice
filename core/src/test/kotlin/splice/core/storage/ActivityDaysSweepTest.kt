// NEW: V4-273 — a day file goes when its day leaves the store's window, not when the store next
// writes. Before, the only sweep ran inside an append (the first of each new day), so a store that
// wrote nothing kept every day past its window on disk: yesterday's activity labels (kept for today
// only since V4-261), message edges past their window and trace days past traceRetentionDays.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

private const val DAY_MS = 86_400_000L
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z
private const val MIDNIGHT = DAY_ONE + 14 * 3_600_000L // 2026-09-19T00:00Z
private val TODAY = LocalDate.parse("2026-09-18")

// The second run's wait: the first run reads the clock this far before the second midnight, so it
// re-arms this far out, and the test moves the clock past that midnight inside the window.
private const val SECOND_WAIT_MS = 2_000L

class ActivityDaysSweepTest {

    @TempDir
    lateinit var tmp: Path

    /** The three stores ActivityDays keeps: activity labels (today only), message edges (the
     *  activityRetentionDays default) and a head's trace days (the traceRetentionDays default). */
    private val stores = listOf("labels" to 1, "edges" to 90, "kimi" to 7)

    private fun seed(dir: Path, prefix: String, day: LocalDate, vararg siblings: String): Path {
        Files.createDirectories(dir)
        val file = dir.resolve("$prefix-$day.jsonl")
        Files.writeString(file, "{\"day\":\"$day\"}\n")
        siblings.forEach { Files.writeString(dir.resolve("${file.fileName}$it"), "") }
        return file
    }

    @Test
    fun `a store opened over days past its window leaves none of them on disk, with no write - V4-273`() {
        for ((prefix, retention) in stores) {
            val dir = tmp.resolve(prefix)
            val past = seed(dir, prefix, TODAY.minusDays(retention.toLong()), ".lock", ".1")
            val kept = seed(dir, prefix, TODAY.minusDays(retention.toLong() - 1))

            val _ = ActivityDays(dir, prefix, retention, WallClock { DAY_ONE })

            val siblings = listOf(".lock", ".1").map { past.resolveSibling("${past.fileName}$it") }
            for (gone in listOf(past) + siblings) {
                assertFalse(Files.exists(gone), "$prefix (window $retention): $gone is past the window at open")
            }
            assertTrue(Files.exists(kept), "$prefix (window $retention): the window's oldest day stays")
        }
    }

    /** Polls [done] with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within 10 s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    @Test
    fun `an idle store drops a day at each UTC midnight it leaves the window, on the file lane - V4-273`() {
        val now = AtomicLong(MIDNIGHT - 1)
        val days = stores.map { (prefix, retention) ->
            val dir = tmp.resolve(prefix)
            val first = seed(dir, prefix, TODAY.minusDays(retention - 1L), ".lock")
            val second = seed(dir, prefix, TODAY.minusDays(retention - 2L))
            val _ = ActivityDays(dir, prefix, retention, WallClock { now.get() })
            Triple(prefix, first, second)
        }
        assertTrue(days.all { (_, first, _) -> Files.exists(first) }, "every first day is in its window at open")

        // Opened a ms before midnight, each store's sweep runs a second later; by then it is the next day.
        now.set(MIDNIGHT + DAY_MS - SECOND_WAIT_MS)
        awaitUntil("each store's first day went at the first midnight, with no write") {
            days.none { (_, first, _) -> Files.exists(first) }
        }
        for ((prefix, first, second) in days) {
            assertFalse(Files.exists(first.resolveSibling("${first.fileName}.lock")), "$prefix: its lock went too")
            assertTrue(Files.exists(second), "$prefix: the next day is still in the window")
        }

        now.set(MIDNIGHT + DAY_MS + 1)
        awaitUntil("each store's sweep armed the next midnight, and its second day went there") {
            days.none { (_, _, second) -> Files.exists(second) }
        }
    }
}

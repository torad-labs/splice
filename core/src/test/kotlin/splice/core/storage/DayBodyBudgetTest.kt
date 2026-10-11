package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonlForce
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

// Tiny synthetic packs exercise byte-exact admission without allocating production-sized stores.
private const val BODY_TEST_BYTES = 16L
private const val BODY_TEST_FLOOR = 64L
private val BODY_TEST_NOW = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

class DayBodyBudgetTest {
    @Test
    fun `all heads and both formats share capacity while metadata and today survive`(@TempDir dir: Path) {
        val oldest = day(dir, "a", "2026-10-03")
        val sibling = day(dir, "b", "2026-10-03")
        val yesterday = day(dir, "c", "2026-10-04")
        val today = day(dir, "a", "2026-10-05")
        val legacy = pack(oldest, DAY_BODY_SUFFIX)
        val other = pack(sibling)
        val newer = pack(yesterday)
        val current = pack(today)
        val budget = DayBodyBudget(
            BODY_TEST_BYTES * 4,
            0,
            DayVolumeSpace { Long.MAX_VALUE },
            WallClock { BODY_TEST_NOW },
        )
        budget.admit(current, 1)
        assertFalse(Files.exists(legacy))
        assertFalse(Files.exists(other), "the complete oldest day is evicted across heads")
        assertTrue(Files.exists(newer), "a newer complete day remains while capacity suffices")
        assertTrue(Files.exists(current), "today is never evicted")
        assertTrue(Files.exists(oldest) && Files.exists(sibling), "body eviction never deletes trace metadata")
        assertTrue(budget.evicted(oldest) && budget.evicted(sibling))
    }

    @Test
    fun `free space wins over unused capacity and carries its refusal reason`(@TempDir dir: Path) {
        val current = pack(day(dir, "a", "2026-10-05"))
        val budget = DayBodyBudget(
            Long.MAX_VALUE,
            BODY_TEST_FLOOR,
            DayVolumeSpace { BODY_TEST_FLOOR },
            WallClock { BODY_TEST_NOW },
        )
        val refused = assertThrows(DayBodyCapacityException::class.java) { budget.admit(current, 1) }
        assertEquals("trace volume free-space floor reached", refused.reason)
        assertEquals(BODY_TEST_BYTES, Files.size(current))
    }

    @Test
    fun `an overfull current day refuses growth without deleting current packs`(@TempDir dir: Path) {
        val first = pack(day(dir, "a", "2026-10-05"))
        val second = pack(day(dir, "b", "2026-10-05"))
        val budget = DayBodyBudget(
            BODY_TEST_BYTES * 2,
            0,
            DayVolumeSpace { Long.MAX_VALUE },
            WallClock { BODY_TEST_NOW },
        )
        val refused = assertThrows(DayBodyCapacityException::class.java) { budget.admit(first, 1) }
        assertEquals("shared rolling trace body budget exhausted", refused.reason)
        assertTrue(Files.exists(first) && Files.exists(second))
    }

    @Test
    fun `an eviction marker is forced and published before its pack unlinks`(@TempDir dir: Path) {
        val oldest = day(dir, "a", "2026-10-03")
        val oldPack = pack(oldest)
        val current = pack(day(dir, "a", "2026-10-05"))
        val marker = oldest.resolveSibling("${oldest.fileName}$DAY_BODY_EVICTED_SUFFIX")
        val events = ArrayList<String>()
        val budget = DayBodyBudget(
            BODY_TEST_BYTES * 2,
            0,
            DayVolumeSpace { Long.MAX_VALUE },
            WallClock { BODY_TEST_NOW },
            JsonlForce { file, channel ->
                assertTrue(Files.exists(oldPack), "neither force may follow a pack unlink")
                if (Files.isDirectory(file)) {
                    assertEquals(listOf("file"), events)
                    assertTrue(Files.exists(marker), "the marker is published before its directory is forced")
                    events += "directory"
                } else {
                    assertFalse(Files.exists(marker), "the temporary marker is forced before publication")
                    assertEquals(BODY_BUDGET_EVICTED_REASON + "\n", Files.readString(file))
                    events += "file"
                }
                channel.force(true)
            },
        )
        budget.admit(current, 1)
        assertEquals(listOf("file", "directory"), events)
        assertFalse(Files.exists(oldPack))
        assertTrue(DayBodyBudget().evicted(oldest), "a fresh policy reads the published reason")
    }

    @Test
    fun `a failed marker force never unlinks its pack`(@TempDir root: Path) {
        for (failureAt in listOf("file", "directory")) {
            val dir = Files.createDirectory(root.resolve(failureAt))
            val oldPack = pack(day(dir, "a", "2026-10-03"))
            val current = pack(day(dir, "a", "2026-10-05"))
            val budget = DayBodyBudget(
                BODY_TEST_BYTES * 2,
                0,
                DayVolumeSpace { Long.MAX_VALUE },
                WallClock { BODY_TEST_NOW },
                JsonlForce { file, channel ->
                    val step = if (Files.isDirectory(file)) "directory" else "file"
                    if (step == failureAt) throw IOException("synthetic $step force refused")
                    channel.force(true)
                },
            )
            assertThrows(IOException::class.java) { budget.admit(current, 1) }
            assertTrue(Files.exists(oldPack) && Files.exists(current), "a failed force cannot delete bodies")
        }
    }

    @Test
    fun `retention and purge discover marker only days and remove their temporary siblings`(@TempDir dir: Path) {
        val old = dir.resolve("a-2026-10-03.jsonl$DAY_BODY_EVICTED_SUFFIX")
        val current = dir.resolve("b-2026-10-05.jsonl$DAY_BODY_EVICTED_SUFFIX")
        for (marker in listOf(old, current)) {
            Files.writeString(marker, BODY_BUDGET_EVICTED_REASON + "\n")
            Files.writeString(marker.resolveSibling("${marker.fileName}.tmp"), "synthetic interrupted marker")
        }
        val interrupted = dir.resolve("orphan-2026-10-03.jsonl$DAY_BODY_EVICTED_SUFFIX.tmp")
        Files.createFile(interrupted)
        assertEquals(setOf("a", "b", "orphan"), DayFileStores(dir).prefixes())
        DayFiles(dir, "orphan").deleteBefore(LocalDate.parse("2026-10-04"))
        assertFalse(Files.exists(interrupted), "even an empty unpublished marker is discovered and ages out")
        DayFiles(dir, "a").deleteBefore(LocalDate.parse("2026-10-04"))
        assertFalse(Files.exists(old))
        assertFalse(Files.exists(old.resolveSibling("${old.fileName}.tmp")))
        assertTrue(Files.exists(current), "retention does not cross heads or delete the current day")
        assertTrue(DayFiles(dir, "b").purge() is DayPurge.Listed)
        assertFalse(Files.exists(current))
        assertFalse(Files.exists(current.resolveSibling("${current.fileName}.tmp")))
        assertEquals(emptySet<String>(), DayFileStores(dir).prefixes())
    }

    private fun day(dir: Path, head: String, date: String): Path =
        Files.writeString(dir.resolve("$head-$date.jsonl"), "{\"kind\":\"synthetic\"}\n")

    private fun pack(day: Path, suffix: String = DAY_BODY_V2_SUFFIX): Path =
        Files.write(day.resolveSibling("${day.fileName}$suffix"), ByteArray(BODY_TEST_BYTES.toInt()))
}

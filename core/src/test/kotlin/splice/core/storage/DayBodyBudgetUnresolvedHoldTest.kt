// NEW: Oct 26, 2026, on review of the budget's lock hold: the directory lock reads a path by its text, so a hold opened
// on `dir/missing/..` takes the lock of `dir`, and so does one opened through a regular file or a link to nothing. The
// filesystem cannot resolve any of the three. A call inside that hold for `dir` spelled otherwise, and a call for
// another directory, still have to be decided by the files of the directory they are for, exactly as a call outside
// any hold is: they list their own directory, and grant, evict and remove as they would alone.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DayBodyBudgetUnresolvedHoldTest {
    @Test
    fun `a hold through a name that is not there decides each call by its own directory`(@TempDir root: Path) {
        assertEachCallDecidedByItsOwnDirectory(root) { held -> held.resolve("missing").resolve("..") }
    }

    @Test
    fun `a hold through a regular file decides each call by its own directory`(@TempDir root: Path) {
        assertEachCallDecidedByItsOwnDirectory(root) { held -> Files.createFile(held.resolve("a-file")).resolve("..") }
    }

    @Test
    fun `a hold through a link to nothing decides each call by its own directory`(@TempDir root: Path) {
        assertEachCallDecidedByItsOwnDirectory(root) { held ->
            Files.createSymbolicLink(held.resolve("dangling"), Path.of("nowhere")).resolve("..")
        }
    }

    /** Two trace directories of the same four days, a hold opened on [spelled] of the first and, inside it, a body for
     *  today's pack of the first, an index line for today's day file of the first, and a body for today's pack of the
     *  second. Each is asked when its cap is exactly full, so each evicts the oldest completed day by the files of the
     *  directory it is for: the first loses Oct 3 whole, the second only its packs. */
    private fun assertEachCallDecidedByItsOwnDirectory(root: Path, spelled: (Path) -> Path) {
        val held = traceDirectory(root.resolve("held"))
        val other = traceDirectory(root.resolve("other"))
        val budget = DayBodyBudget(
            PACK_BYTES * DAY_PACKS.size,
            0,
            DayVolumeSpace { Long.MAX_VALUE },
            WallClock { NOW },
            indexMaxBytes = Files.size(held.resolve(TODAY)) * DAY_PACKS.size,
        )

        budget.withLock(spelled(held)) {
            budget.admit(held.resolve(TODAY + DAY_BODY_V2_SUFFIX), 1)
            assertNull(budget.admitIndex(held.resolve(TODAY), 1), "the index line is granted")
            budget.admit(other.resolve(TODAY + DAY_BODY_V2_SUFFIX), 1)
        }

        assertEquals(HELD_AFTERWARDS, traceFiles(held), "the first directory lost its oldest completed day whole")
        assertEquals(OTHER_AFTERWARDS, traceFiles(other), "the second lost the packs of that day and kept the rest")
    }

    /** [dir] with each of [DAY_PACKS]: the day's index file, one line, and its body pack of [PACK_BYTES]. */
    private fun traceDirectory(dir: Path): Path {
        Files.createDirectory(dir)
        for ((day, suffix) in DAY_PACKS) {
            Files.writeString(dir.resolve(day), "{\"kind\":\"synthetic\"}\n")
            Files.write(dir.resolve(day + suffix), ByteArray(PACK_BYTES.toInt()))
        }
        return dir
    }

    /** The names of the dated trace files in [dir]: its index files, body packs and eviction markers, sorted. */
    private fun traceFiles(dir: Path): List<String> =
        Files.list(dir).use { entries -> entries.map { it.fileName.toString() }.toList() }
            .filter { "-2026-" in it }
            .sorted()
}

private const val OLDEST = "a-2026-10-03.jsonl"
private const val SIBLING = "b-2026-10-03.jsonl"
private const val YESTERDAY = "c-2026-10-04.jsonl"
private const val TODAY = "a-2026-10-05.jsonl"

// why: the oldest completed day holds two heads' packs, one of each format, so evicting it takes both formats and heads
private val DAY_PACKS = listOf(
    OLDEST to DAY_BODY_SUFFIX,
    SIBLING to DAY_BODY_V2_SUFFIX,
    YESTERDAY to DAY_BODY_V2_SUFFIX,
    TODAY to DAY_BODY_V2_SUFFIX,
)

// why: the first directory's Oct 3 is removed whole, its index files and the markers its packs left included
private val HELD_AFTERWARDS = listOf(
    YESTERDAY,
    YESTERDAY + DAY_BODY_V2_SUFFIX,
    TODAY,
    TODAY + DAY_BODY_V2_SUFFIX,
).sorted()

// why: the second directory's Oct 3 packs are replaced by the markers that say why, and its index files stay
private val OTHER_AFTERWARDS = listOf(
    OLDEST,
    OLDEST + DAY_BODY_EVICTED_SUFFIX,
    SIBLING,
    SIBLING + DAY_BODY_EVICTED_SUFFIX,
    YESTERDAY,
    YESTERDAY + DAY_BODY_V2_SUFFIX,
    TODAY,
    TODAY + DAY_BODY_V2_SUFFIX,
).sorted()

// why: tiny synthetic packs, so the caps are a few dozen bytes and every call lands on one
private const val PACK_BYTES = 16L
private val NOW = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

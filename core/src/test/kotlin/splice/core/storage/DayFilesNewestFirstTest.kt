// NEW: V4-338 — DayFiles.newestFirst: every line of every day on disk, newest first, one at a time. It is
// the forward read reversed (newest day first, each day's live file before its rolled half), and a visit
// that stops never opens an older day. V4-343: eachFile opens the same files in the same order.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// DR-186's backstop (JsonlSinkTest's idiom): a read that never reaches its end wedges the suite rather
// than failing, so each case is failed by name past this.
private const val HANG_BACKSTOP_S = 60L

@Timeout(value = HANG_BACKSTOP_S, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DayFilesNewestFirstTest {

    private fun DayFiles.newest(stopAfter: Int = Int.MAX_VALUE): List<String> =
        mutableListOf<String>().also { lines ->
            newestFirst { line ->
                lines += line.text()
                lines.size < stopAfter
            }
        }

    private fun twoDays(dir: Path): Path {
        Files.writeString(dir.resolve("kimi-2026-09-18.jsonl"), "d1-a\nd1-b\n")
        Files.writeString(dir.resolve("kimi-2026-09-19.jsonl.1"), "d2-a\nd2-b\n")
        Files.writeString(dir.resolve("kimi-2026-09-19.jsonl"), "d2-c\nd2-d\n")
        Files.writeString(dir.resolve("kimi-2026-09-19.jsonl.lock"), "")
        Files.writeString(dir.resolve("other-2026-09-20.jsonl"), "not kimi\n")
        return dir.resolve("kimi-2026-09-18.jsonl")
    }

    @Test
    fun `newest first is the forward read reversed, across days and a rolled half`(@TempDir dir: Path) {
        twoDays(dir)
        val days = DayFiles(dir, "kimi")

        assertEquals(listOf("d2-d", "d2-c", "d2-b", "d2-a", "d1-b", "d1-a"), days.newest())
        assertEquals(days.lines().toList().reversed(), days.newest())
    }

    @Test
    fun `a visit that stops never opens an older day, and a full read says the day it cannot read`(
        @TempDir dir: Path,
    ) {
        val dayOne = twoDays(dir)
        val days = DayFiles(dir, "kimi")
        Files.setPosixFilePermissions(dayOne, PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(dayOne), "root reads whatever the mode")
            assertEquals(listOf("d2-d", "d2-c", "d2-b"), days.newest(stopAfter = 3))
            assertThrows(IOException::class.java) { days.newest() }
        } finally {
            Files.setPosixFilePermissions(dayOne, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a directory that cannot be listed throws, and one with no days has no lines`(@TempDir dir: Path) {
        val notADir = Files.writeString(dir.resolve("trace"), "a file where the directory should be")

        assertThrows(IOException::class.java) { DayFiles(notADir, "kimi").newest() }
        assertEquals(emptyList<String>(), DayFiles(dir, "kimi").newest())
    }

    @Test
    fun `each file is opened in the newest-first order, and a day with no rolled half has one`(@TempDir dir: Path) {
        // V4-343: the files newestFirst reads, for a reader that reads each its own way.
        twoDays(dir)

        val opened = DayFiles(dir, "kimi").eachFile { file -> String(file.bytes(0L, file.size.toInt())) }

        assertEquals(listOf("d2-c\nd2-d\n", "d2-a\nd2-b\n", "d1-a\nd1-b\n"), opened)
    }
}

// NEW: v0.4.0 release (the V4-130 followup) — a day file JsonlSink rotated at DAY_MAX_BYTES keeps its
// older half in `<day>.jsonl.1`, and the read skipped it: that day's view silently started halfway.
// The rolled generation is read first, so the day comes back whole and in order.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

class ActivityDaysRolledTest {

    @Test
    fun `a rotated day reads its rolled generation first, then the live file`(@TempDir dir: Path) {
        val days = ActivityDays(dir, "kimi", retentionDays = 7, clock = WallClock { DAY_ONE })
        Files.writeString(dir.resolve("kimi-2026-09-18.jsonl.1"), "{\"n\":1}\n{\"n\":2}\n")
        Files.writeString(dir.resolve("kimi-2026-09-18.jsonl"), "{\"n\":3}\n")

        assertEquals(listOf("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"), days.lines().toList())
    }

    @Test
    fun `a day that never rotated reads as before`(@TempDir dir: Path) {
        val days = ActivityDays(dir, "kimi", retentionDays = 7, clock = WallClock { DAY_ONE })
        Files.writeString(dir.resolve("kimi-2026-09-18.jsonl"), "{\"n\":1}\n")

        assertEquals(listOf("{\"n\":1}"), days.lines().toList())
    }

    @Test
    fun `a character a disk-full append cut short costs its own line, never the day - V4-286`(@TempDir dir: Path) {
        val days = ActivityDays(dir, "kimi", retentionDays = 7, clock = WallClock { DAY_ONE })
        // The first two of the euro sign's three bytes, as JsonlSink's header records an ENOSPC append leaving.
        val cut = byteArrayOf(0xE2.toByte(), 0x82.toByte())
        val bytes = "{\"n\":1}\n{\"price\":\"".toByteArray() + cut + "\n{\"n\":2}\n".toByteArray()
        Files.write(dir.resolve("kimi-2026-09-18.jsonl"), bytes)

        val lines = days.lines().toList()

        assertEquals(3, lines.size, "one line per row, the cut one left for its reader to skip: $lines")
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), listOf(lines.first(), lines.last()), "the whole day: $lines")
    }

    @Test
    fun `a day that is there and cannot be read throws why, never reads as an empty day - V4-286`(@TempDir dir: Path) {
        val days = ActivityDays(dir, "kimi", retentionDays = 7, clock = WallClock { DAY_ONE })
        val day = dir.resolve("kimi-2026-09-18.jsonl")
        Files.writeString(day, "{\"n\":1}\n")
        Files.setPosixFilePermissions(day, PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(day), "root reads a file whatever its mode")
            val thrown = assertThrows(IOException::class.java) { val _ = days.lines().toList() }
            assertTrue(thrown is AccessDeniedException, "$thrown")
        } finally {
            Files.setPosixFilePermissions(day, PosixFilePermissions.fromString("rw-------"))
        }
    }
}

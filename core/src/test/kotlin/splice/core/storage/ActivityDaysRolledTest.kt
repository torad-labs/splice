// NEW: v0.4.0 release (the V4-130 followup) — a day file JsonlSink rotated at DAY_MAX_BYTES keeps its
// older half in `<day>.jsonl.1`, and the read skipped it: that day's view silently started halfway.
// The rolled generation is read first, so the day comes back whole and in order.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

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
}

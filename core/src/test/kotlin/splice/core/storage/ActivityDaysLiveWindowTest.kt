// NEW: Oct 10, 2026 — a store whose window is asked at every use follows a change with no restart.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

private const val NOW = 1_789_725_600_000L // 2026-09-18T10:00Z
private val TODAY_DAY = LocalDate.parse("2026-09-18")

class ActivityDaysLiveWindowTest {

    @TempDir
    lateinit var tmp: Path

    private fun seed(day: LocalDate) {
        Files.createDirectories(tmp)
        Files.writeString(tmp.resolve("head-$day.jsonl"), "{\"day\":\"$day\"}\n")
    }

    @Test
    fun `lengthening and shortening the window moves what a read returns on the next read`() {
        // Days are seeded after construction so the open-time sweep does not decide the test.
        var days = 7
        val store = ActivityDays(tmp, "head", days, WallClock { NOW }, live = RetentionDays { days })
        for (back in 0L..20L) seed(TODAY_DAY.minusDays(back))

        assertEquals(7, store.lines().count(), "the week it was built with")
        days = 20
        assertEquals(20, store.lines().count(), "a longer window shows the older days at once")
        days = 1
        assertEquals(1, store.lines().count(), "today only shows today")
    }
}

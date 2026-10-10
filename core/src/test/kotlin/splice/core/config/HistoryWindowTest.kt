// NEW: Oct 10, 2026 — the one history window a person sets, and the window every install already
// had. Read through ConfigService, which is the only thing that may mint a SpliceConfig, so each
// case is the merge an operator's own splice.toml produces rather than a hand-built map.
//
// WHAT THESE PIN, in Marlin's words: an upgrade never shortens history on its own, so a 90 stays 90
// and splice deletes nothing because it learned a new name for the question. 35 is the default only
// where there is no earlier setting, which is a fresh install — pinned beside the starter that
// writes it (FreshInstallHistoryWindowTest), because that file is where the number lives.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HistoryWindowTest {
    @TempDir
    lateinit var tmp: Path

    /** The [defaults] table of an operator's splice.toml: the layer they write a knob in. */
    private fun written(vararg pairs: Pair<String, String>) = ConfigService(
        statePaths = StatePaths(baseOverride = tmp.resolve("state")),
        headOverrides = pairs.toMap(),
        envReader = { null },
    ).getConfig()

    @Test
    fun `an install that predates the setting keeps the records window it already had`() {
        assertEquals(
            HistoryWindow(90),
            written().historyWindow,
            "the 90 days every install held by default is the window it keeps",
        )
        assertEquals(HistoryWindow(120), written("perfArchiveRetentionDays" to "120").historyWindow)
    }

    @Test
    fun `the window the person set wins, a day count and the word forever alike`() {
        val short = written("historyRetentionDays" to "7", "perfArchiveRetentionDays" to "90")
        assertEquals(HistoryWindow(7), short.historyWindow, "their own setting outranks what they had")

        val kept = written("historyRetentionDays" to "forever").historyWindow
        assertTrue(kept.forever, "forever is a value a person may choose")
        assertNull(kept.cutoffMs(1_000_000L), "a window that keeps everything names no cutoff to trim against")
        assertNull(kept.hours, "and no number of hours, because none of them says forever")
        assertEquals("forever", kept.text)
    }

    @Test
    fun `a word that names no window is refused, and the window stays what it was`() {
        listOf("forver", "-5", "a month", "").forEach { word ->
            assertEquals(
                HistoryWindow(90),
                written("historyRetentionDays" to word).historyWindow,
                "a window splice cannot read must never become a shorter one: $word",
            )
        }
    }

    @Test
    fun `the legacy archive-off does not delete the hourly totals it never governed`() {
        val carried = written("perfArchiveRetentionDays" to "0").historyWindow
        assertEquals(
            HistoryWindow(HISTORY_DEFAULT_DAYS),
            carried,
            "0 said keep no retired perf generations, which says nothing about the hours a person holds",
        )
        assertFalse(carried.nothing)
        assertTrue(written("historyRetentionDays" to "0").historyWindow.nothing, "a 0 they wrote themselves holds")
    }

    @Test
    fun `keeping nothing keeps the day that is running, and drops it at midnight where the daemon runs`() {
        val chicago = ZoneId.of("America/Chicago")
        val nothing = HistoryWindow(0, chicago)
        // 01:00 UTC on Oct 10, 2026, which is 20:00 on Oct 9 in Chicago: the running day began at
        // 05:00 UTC that morning, and an hour before it is a finished day that goes.
        val at = ZonedDateTime.of(2026, 10, 10, 1, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 10, 9, 0, 0, 0, 0, chicago).toInstant().toEpochMilli()

        assertEquals(midnight, nothing.cutoffMs(at), "the cutoff is the operator's own midnight, never UTC's")
        assertTrue(nothing.nothing, "and it reads as keeping nothing, which is what the menu offers")
        assertEquals("0", nothing.text)
        assertEquals(
            midnight + 24 * 60 * 60 * 1000,
            HistoryWindow(0, chicago).cutoffMs(at + 5 * 60 * 60 * 1000),
            "five hours later the Chicago day has rolled, and yesterday's hours go with it",
        )
    }

    @Test
    fun `the window a person sets reaches the daemon through the environment too`() {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = tmp.resolve("env-state")),
            headOverrides = mapOf("historyRetentionDays" to "90"),
            envReader = { name -> "14".takeIf { name == "SPLICE_HISTORY_RETENTION_DAYS" } },
        ).getConfig()

        assertEquals(HistoryWindow(14), config.historyWindow, "env beats the file, as it does for every knob")
    }
}

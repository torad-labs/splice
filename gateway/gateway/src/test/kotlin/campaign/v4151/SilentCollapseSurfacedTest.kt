// NEW: V4-151 — the three gateway reads the widened kt-no-silent-result-collapse wall found hiding a
// real failure. Each degraded exactly as it does now (empty history, zero stats, no quota) but said
// nothing, so a corrupt or unreadable file was indistinguishable from a first run. Each arm pins
// the trace the degrade now leaves, and the quiet case beside it: a genuinely absent file stays
// silent, the DR-58/DR-60 class law its siblings already follow.
package campaign.v4151

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.gateway.compact.CompactStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.QuotaTracker
import java.nio.file.Files
import java.nio.file.Path

class SilentCollapseSurfacedTest {

    @TempDir
    lateinit var dir: Path

    private val lines = mutableListOf<String>()
    private val sink = LogSink { lines += it }

    @Test
    fun `a corrupt economics file degrades to empty and says so`() {
        val file = dir.resolve("economics.json").also { Files.writeString(it, "{not json") }
        assertEquals(emptyList<Any>(), EconomicsStore(file, WallClock { 0L }, sink).read())
        assertEquals(1, lines.size, "one trace for the unreadable history: $lines")
        assertTrue(lines.single().contains("[economics]"), lines.single())
    }

    @Test
    fun `an absent economics file is a quiet first run`() {
        EconomicsStore(dir.resolve("economics.json"), WallClock { 0L }, sink).read()
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a corrupt compact stats line is skipped and says so once`() {
        val file = dir.resolve("compact-stats.jsonl")
        Files.writeString(file, "{\"outcome\":\"ok\"}\n{torn\n{also torn\n")
        val stats = CompactStats(file, WallClock { 0L }, sink)
        assertEquals(1, stats.read().total)
        stats.read()
        assertEquals(1, lines.size, "one trace per instance, not per row or per read: $lines")
        assertTrue(lines.single().contains("[compact]"), lines.single())
    }

    // An UNREADABLE path, not corrupt content: QuotaJson.decode (core, QuotaJson.kt:27) already
    // collapses bad JSON to null before this catch sees it, and :core is outside the widened globs
    // (the V4-151 followup). What this catch owns is the read itself; a directory at the path fails
    // it with a non-NoSuch error on any filesystem and under any uid.
    @Test
    fun `an unreadable quota file reads as no snapshot and says so`() {
        val file = dir.resolve("quota.json").also { Files.createDirectory(it) }
        assertNull(QuotaTracker(file, WallClock { 0L }, sink).snapshot())
        assertEquals(1, lines.size, "one trace for the unreadable quota file: $lines")
        assertTrue(lines.single().contains("[quota]"), lines.single())
    }

    @Test
    fun `an absent quota file is a quiet first run`() {
        assertNull(QuotaTracker(dir.resolve("quota.json"), WallClock { 0L }, sink).snapshot())
        assertEquals(emptyList<String>(), lines)
    }
}

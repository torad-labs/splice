// DR-60 absence-class arms for the gateway's display-path state readers (ratelimit HUD, compact
// stats, perf stats). The class law, display flavor: genuine absence (NoSuch + no NOFOLLOW entry)
// stays the quiet empty; an INACCESSIBLE file degrades the same but logs — once per unreadable
// episode (these reads run per HUD tick / stats render), re-armed by a healthy read or proven
// absence.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.RateLimitFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class GatewayStateAbsenceTest {

    private fun lockedFile(tmp: Path, name: String, content: String): Pair<Path, Path> {
        val locked = Files.createDirectories(tmp.resolve("locked"))
        val file = locked.resolve(name)
        Files.writeString(file, content)
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        return file to locked
    }

    private fun unlock(dir: Path) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }

    @Test
    fun `an inaccessible ratelimit file logs once and degrades, absence stays quiet - DR-60`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()
        val absent = RateLimitFile(tmp.resolve("never.json"), LogSink { log += it })
        assertNull(absent.read())
        assertTrue(log.isEmpty(), "true absence is the quiet no-state null: $log")

        val (file, locked) = lockedFile(tmp, "r.json", """{"unified":{}}""")
        val reader = RateLimitFile(file, LogSink { log += it })
        try {
            assertNull(reader.read(), "inaccessible degrades to the same null")
            assertNull(reader.read())
        } finally {
            unlock(locked)
        }
        assertEquals(1, log.count { it.contains("unreadable") }, "one line per episode: $log")
        assertNotNull(reader.read(), "healthy read resumes and re-arms")
        Files.delete(file)
        assertNull(reader.read())
        assertEquals(1, log.count { it.contains("unreadable") }, "absence after recovery stays quiet: $log")
    }

    @Test
    fun `an inaccessible compact stats file logs once and renders empty - DR-60`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()
        val absentStats = CompactStats(tmp.resolve("never.jsonl"), log = LogSink { log += it })
        assertEquals(0, absentStats.read().total)
        assertTrue(log.isEmpty(), "true absence is the quiet zero-stats empty: $log")

        val (file, locked) = lockedFile(tmp, "c.jsonl", """{"outcome":"ok"}""")
        val stats = CompactStats(file, log = LogSink { log += it })
        try {
            assertEquals(0, stats.read().total, "inaccessible renders the same empty")
            assertEquals(0, stats.read().total)
        } finally {
            unlock(locked)
        }
        assertEquals(1, log.count { it.contains("unreadable") }, "one line per episode: $log")
    }

    @Test
    fun `an inaccessible perf log logs once and renders empty - DR-60`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()
        val absentStats = PerfStats(tmp.resolve("never.jsonl"), log = LogSink { log += it })
        assertTrue(absentStats.tailNumeric().isEmpty())
        assertTrue(log.isEmpty(), "true absence is the quiet empty: $log")

        val (file, locked) = lockedFile(tmp, "p.jsonl", """{"total":1}""")
        val stats = PerfStats(file, log = LogSink { log += it })
        try {
            assertTrue(stats.tailNumeric().isEmpty(), "inaccessible renders the same empty")
            assertTrue(stats.tailNumeric().isEmpty())
        } finally {
            unlock(locked)
        }
        assertEquals(1, log.count { it.contains("unreadable") }, "one line per episode: $log")
    }
}

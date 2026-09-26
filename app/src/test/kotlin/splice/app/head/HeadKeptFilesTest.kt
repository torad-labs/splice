// NEW: V4-286 — the daemon-start sweep of a removed head's files says what it did. A trace day it could
// not delete was logged as deleted, a trace dir it could not list was read as empty in silence, and a
// file named for a date not on the calendar threw out of ofRemovedHeads, which Daemon.start calls
// outside any try, so the daemon did not start.
package splice.app.head

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

class HeadKeptFilesTest {

    @TempDir
    lateinit var tmp: Path

    private val lines = CopyOnWriteArrayList<String>()
    private val paths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }

    private fun kept() = HeadKeptFiles(paths, LogSink { lines += it })

    private fun seed(file: Path): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}\n")
        return file
    }

    /** [block] with [dir] held at [mode], restored to owner rwx after; skipped where the mode does not bind. */
    private fun withMode(dir: Path, mode: String, block: () -> Unit) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode))
        try {
            assumeFalse(Files.isWritable(dir) && Files.isReadable(dir), "root reads and writes whatever the mode")
            block()
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a removed head's trace day that could not be deleted is logged as still there, never as deleted`() {
        val day = seed(paths.traceDir.resolve("gone-2026-09-18.jsonl"))

        withMode(paths.traceDir, "r-x------") { kept().ofRemovedHeads(emptySet()) }

        assertTrue(Files.exists(day))
        assertFalse(lines.any { it.contains("trace day file(s) deleted") }, "nothing was deleted: $lines")
        assertTrue(lines.any { it.startsWith("[gone] trace file $day could not be deleted (") }, "$lines")
    }

    @Test
    fun `a trace dir that cannot be listed is logged at the start, never read as holding nothing`() {
        seed(paths.traceDir.resolve("gone-2026-09-18.jsonl"))

        withMode(paths.traceDir, "-wx------") { kept().ofRemovedHeads(emptySet()) }

        assertTrue(lines.any { it.startsWith("[kept-files] ${paths.traceDir} could not be listed (") }, "$lines")
    }

    @Test
    fun `a file named for a date not on the calendar does not stop the start, and the head's days still go`() {
        val notADay = seed(paths.traceDir.resolve("gone-2026-02-29.jsonl"))
        val day = seed(paths.traceDir.resolve("gone-2026-09-18.jsonl"))

        kept().ofRemovedHeads(emptySet())

        assertFalse(Files.exists(day), "the removed head's trace day went: $lines")
        assertTrue(Files.exists(notADay), "a name that is no day is not a trace day")
    }
}

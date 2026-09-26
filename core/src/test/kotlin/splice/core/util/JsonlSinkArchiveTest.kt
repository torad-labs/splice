// NEW: V4-133 — JsonlSink.appendLine's RotationArchive hook. NO_ARCHIVE (every caller before this
// row) must keep discarding exactly as before; a real hook must see the rolled generation's bytes
// BEFORE JsonlSink overwrites it, and a hook that throws must degrade to the old discard rather
// than corrupt or block the append it rides on.
//
// maxBytes = 1 forces every append past the first to rotate (each line alone already exceeds it),
// which makes the sequence easy to follow: appending A, B, C, D rotates the LIVE content into .1
// each time — so appending C rotates "B" in, but only after handing the archive hook the ".1" it is
// about to replace, which is "A" (parked there by appending B, which had nothing to archive since
// no .1 existed yet).
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JsonlSinkArchiveTest {

    @Test
    fun `NO_ARCHIVE keeps discarding the rolled generation, exactly as every caller before V4-133`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("perf.jsonl")
        JsonlSink.appendLine(file, "A", maxBytes = 1, archive = JsonlSink.NO_ARCHIVE)
        JsonlSink.appendLine(file, "B", maxBytes = 1, archive = JsonlSink.NO_ARCHIVE)
        // "A" rotated into .1 by the append above; this one rotates "B" in and drops "A" for good.
        JsonlSink.appendLine(file, "C", maxBytes = 1, archive = JsonlSink.NO_ARCHIVE)
        assertEquals(listOf("B"), Files.readAllLines(file.resolveSibling("perf.jsonl.1")))
        assertEquals(listOf("C"), Files.readAllLines(file))
    }

    @Test
    fun `a real archive receives the rolled generation's bytes before JsonlSink overwrites it`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val archiveDir = tmp.resolve("archive")
        Files.createDirectories(archiveDir)
        val seen = mutableListOf<String>()
        val archive = RotationArchive { rolled -> seen += Files.readString(rolled) }

        JsonlSink.appendLine(file, "A", maxBytes = 1, archive = archive) // file: A
        JsonlSink.appendLine(file, "B", maxBytes = 1, archive = archive) // rotate, no .1 yet: file: B, .1: A
        JsonlSink.appendLine(file, "C", maxBytes = 1, archive = archive) // rotate, .1=A archived: file: C, .1: B
        JsonlSink.appendLine(file, "D", maxBytes = 1, archive = archive) // rotate, .1=B archived: file: D, .1: C

        assertEquals(listOf("A\n", "B\n"), seen, "each generation is archived in the order it was rolled out")
        assertEquals(listOf("C"), Files.readAllLines(file.resolveSibling("perf.jsonl.1")))
        assertEquals(listOf("D"), Files.readAllLines(file))
    }

    @Test
    fun `a throwing archive hook degrades to the old discard, never a lost or corrupted append`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val throwing = RotationArchive { throw java.io.IOException("archive dir is unwritable") }
        JsonlSink.appendLine(file, "A", maxBytes = 1, archive = throwing)
        JsonlSink.appendLine(file, "B", maxBytes = 1, archive = throwing)
        // .1 ("A") exists here, so the throwing hook fires — rotation and the append must survive it.
        JsonlSink.appendLine(file, "C", maxBytes = 1, archive = throwing)

        assertTrue(Files.exists(file.resolveSibling("perf.jsonl.1")), "rotation must proceed despite the throw")
        assertEquals(listOf("B"), Files.readAllLines(file.resolveSibling("perf.jsonl.1")))
        assertEquals(listOf("C"), Files.readAllLines(file), "the append itself is never lost")
    }
}

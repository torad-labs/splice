// NEW: V4-169 — the one rewrite both resume moments share, pinned on its own: which rows move,
// which bytes stay, and that a failure is thrown rather than half-applied.
package splice.core.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val USER_ROW = """{"type":"user","sessionId":"s1","message":{"role":"user","content":"hi"}}"""
private const val FOREIGN_ROW = """{"type":"assistant","sessionId":"s1","message":{"model":"k3-256k","content":[]}}"""
private const val OWN_ROW = """{"type":"assistant","sessionId":"s1","message":{"model":"gpt-5.6-sol","content":[]}}"""
private const val BROKEN_ROW = """{"type":"assistant","message":{"model":"k3-256k" this is not json"""

class TranscriptModelRewriteTest {

    private val rewriter = TranscriptModelRewrite()

    private fun write(path: Path, vararg rows: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, rows.joinToString("\n") + "\n")
    }

    private fun rows(path: Path): List<String> = Files.readString(path).split("\n")

    @Test
    fun `assistant rows on another model move, everything else stays byte-identical`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), USER_ROW, FOREIGN_ROW, OWN_ROW, BROKEN_ROW)

        val rewritten = rewriter.rewrite(transcript, "gpt-5.6-sol")

        assertEquals(1, rewritten)
        val after = rows(transcript)
        assertEquals(USER_ROW, after[0], "a user row is not the rewrite's business")
        assertEquals(
            """{"type":"assistant","sessionId":"s1","message":{"model":"gpt-5.6-sol","content":[]}}""",
            after[1],
        )
        assertEquals(OWN_ROW, after[2], "a row already on the model is not re-encoded")
        assertEquals(BROKEN_ROW, after[3], "an unparseable line is history and is never dropped")
        assertEquals("", after[4], "the trailing newline survives")
    }

    @Test
    fun `the session subdir beside the transcript is rewritten too, and nothing is written when nothing changes`(
        @TempDir dir: Path,
    ) {
        val transcript = write(dir.resolve("s1.jsonl"), OWN_ROW)
        val subagent = write(dir.resolve("s1").resolve("subagents").resolve("agent-1.jsonl"), FOREIGN_ROW, FOREIGN_ROW)
        val untouchedStamp = Files.getLastModifiedTime(transcript)

        assertEquals(2, rewriter.rewrite(transcript, "gpt-5.6-sol"))

        assertEquals(listOf(true, true), rows(subagent).take(2).map { it.contains("\"gpt-5.6-sol\"") })
        assertEquals(untouchedStamp, Files.getLastModifiedTime(transcript), "an unchanged file is not rewritten")
        assertEquals(0, rewriter.rewrite(transcript, "gpt-5.6-sol"), "idempotent: a second pass moves nothing")
    }

    @Test
    fun `a file that cannot be written throws instead of leaving half of history moved`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), FOREIGN_ROW)
        Files.setPosixFilePermissions(transcript, PosixFilePermissions.fromString("r--r--r--"))
        try {
            assertThrows(IOException::class.java) { rewriter.rewrite(transcript, "gpt-5.6-sol") }
            assertEquals(FOREIGN_ROW, rows(transcript)[0], "the file is exactly as it was")
        } finally {
            Files.setPosixFilePermissions(transcript, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }
}

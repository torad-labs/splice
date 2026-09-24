// NEW: V4-169 — the one rewrite both resume moments share, pinned on its own: which rows move,
// which bytes stay, and that a failure is thrown rather than half-applied.
package splice.client.resume

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

/** The roster of a head that serves one model: its pinned one. */
private val SOL_ONLY = listOf("gpt-5.6-sol")

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

        val rewritten = rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY)

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

        assertEquals(2, rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY))

        assertEquals(listOf(true, true), rows(subagent).take(2).map { it.contains("\"gpt-5.6-sol\"") })
        assertEquals(untouchedStamp, Files.getLastModifiedTime(transcript), "an unchanged file is not rewritten")
        assertEquals(0, rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY), "idempotent: a second pass moves nothing")
    }

    // v0.4.0 review round 2: Claude Code refuses to restore only a model OUTSIDE the head's roster, so a
    // row on any model the head serves stays where it is. claude-splice serves opus beside its pinned
    // fable, and its transcript tree is the operator's main ~/.claude/projects: moving an opus session
    // onto fable rewrote history the head could have resumed exactly as it was.
    @Test
    fun `a row on a model the head serves stays, and only a foreign one moves`(@TempDir dir: Path) {
        val servedRow = """{"type":"assistant","sessionId":"s1","message":{"model":"claude-opus-5","content":[]}}"""
        val foreignRow = """{"type":"assistant","sessionId":"s1","message":{"model":"claude-opus-5-5","content":[]}}"""
        val transcript = write(dir.resolve("s1.jsonl"), servedRow, foreignRow)

        val rewritten = rewriter.rewrite(transcript, "claude-fable-5", listOf("claude-fable-5", "claude-opus-5"))

        assertEquals(1, rewritten)
        assertEquals(servedRow, rows(transcript)[0], "a served model is restored as it is, so its row is untouched")
        assertEquals(foreignRow.replace("claude-opus-5-5", "claude-fable-5"), rows(transcript)[1])
    }

    @Test
    fun `a file that cannot be written throws instead of leaving half of history moved`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), FOREIGN_ROW)
        Files.setPosixFilePermissions(transcript, PosixFilePermissions.fromString("r--r--r--"))
        try {
            assertThrows(IOException::class.java) { rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY) }
            assertEquals(FOREIGN_ROW, rows(transcript)[0], "the file is exactly as it was")
        } finally {
            Files.setPosixFilePermissions(transcript, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }
}

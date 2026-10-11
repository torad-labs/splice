// NEW: Oct 10, 2026 — a move that gives rows another model is recorded, so the Sessions page can draw where the
// session changed model; a move that gives none, and a rewrite that finds nothing to move, record nothing.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import splice.sessions.transcript.ModelMove
import java.nio.file.Files
import java.nio.file.Path

class ModelMovesTest {
    private fun row(id: String, model: String, text: String) =
        """{"type":"assistant","message":{"id":"$id","model":"$model","content":[{"type":"text","text":"$text"}]}}"""

    private fun transcript(home: Path, vararg rows: String): Path {
        val file = home.resolve("projects/-w-repo/s1.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, rows.joinToString("\n", postfix = "\n"))
        return file
    }

    @Test
    fun `the move is recorded at its first moved message with the model and the command`(@TempDir home: Path) {
        val state = StatePaths(baseOverride = home.resolve("state"))
        val originals = TranscriptOriginals(state)
        val file = transcript(
            home,
            row("m0", "gpt-6.1", "already here"),
            row("m1", "claude-opus-5", "moved"),
            row("m2", "claude-opus-5", "moved too"),
        )
        val roster = CallingRoster("gpt-6.1", listOf("gpt-6.1"), "gpt")

        assertEquals(2, TranscriptModelRewrite(originals = originals).rewrite(file, roster))

        val moves = originals.moves.of("s1")
        assertEquals(1, moves.size)
        assertEquals(ModelMove("m1", "gpt-6.1", "gpt", moves.single().movedAt), moves.single())
        assertTrue(moves.single().movedAt > 0)
        // The rewrite changed the model, never the id the record points at.
        assertTrue(Files.readString(file).contains("\"id\":\"m1\""))
        assertTrue(Files.isRegularFile(state.modelMovesDir.resolve("s1.json")))
    }

    @Test
    fun `a rewrite with nothing to move records nothing, and a second move is added after the first`(
        @TempDir home: Path,
    ) {
        val originals = TranscriptOriginals(StatePaths(baseOverride = home.resolve("state")))
        val rewriter = TranscriptModelRewrite(originals = originals)
        val file = transcript(home, row("m1", "gpt-6.1", "here"))

        assertEquals(0, rewriter.rewrite(file, CallingRoster("gpt-6.1", listOf("gpt-6.1"), "gpt")))
        assertEquals(emptyList<ModelMove>(), originals.moves.of("s1"))

        Files.writeString(file, row("m1", "gpt-6.1", "here") + "\n" + row("m2", "kimi-k3", "there") + "\n")
        assertEquals(1, rewriter.rewrite(file, CallingRoster("gpt-6.1", listOf("gpt-6.1"), "gpt")))
        Files.writeString(file, Files.readString(file) + row("m3", "gpt-6.1", "back") + "\n")
        assertEquals(listOf("m2"), originals.moves.of("s1").map { it.messageId })
    }
}

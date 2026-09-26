// NEW: V4-169 — the one rewrite both resume moments share, pinned on its own: which rows move,
// which bytes stay, and that a failure is thrown rather than half-applied.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val USER_ROW = """{"type":"user","sessionId":"s1","message":{"role":"user","content":"hi"}}"""
private const val FOREIGN_ROW = """{"type":"assistant","sessionId":"s1","message":{"model":"k3-256k","content":[]}}"""
private const val OWN_ROW = """{"type":"assistant","sessionId":"s1","message":{"model":"gpt-5.6-sol","content":[]}}"""
private const val BROKEN_ROW = """{"type":"assistant","message":{"model":"k3-256k" this is not json"""
private const val KIMI_THINKING_ROW = """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_k",""" +
    """"model":"k3","content":[{"type":"thinking","thinking":"plan","signature":"splice-synth-v1"},""" +
    """{"type":"text","text":"answer"}]}}"""
private const val FABLE_THINKING_ROW = """{"type":"assistant","uuid":"a2","parentUuid":"a1",""" +
    """"message":{"id":"msg_f","model":"claude-fable-5",""" +
    """"content":[{"type":"thinking","thinking":"plan","signature":"EqQBCkYIBxgC"},""" +
    """{"type":"text","text":"answer"}]}}"""

/** The roster of a head that serves one model: its pinned one. */
private val SOL_ONLY = listOf("gpt-5.6-sol")

private const val FOREIGN_ROW_ON_SOL = """{"type":"assistant","sessionId":"s1",""" +
    """"message":{"model":"gpt-5.6-sol","content":[]}}"""

/** A write that puts its first [kept] bytes down and then dies, as a crash or a full disk does. */
private class DyingWrite(private val kept: Int) : TranscriptFs {
    override fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes.copyOf(kept))
        throw IOException("No space left on device")
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        Files.move(source, target, *options)
    }
}

/** A write that lands whole, and a swap that is refused. */
private object RefusedSwap : TranscriptFs {
    override fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes)
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        throw IOException("move refused")
    }
}

class TranscriptModelRewriteTest {

    private val rewriter = TranscriptModelRewrite()

    private fun write(path: Path, vararg rows: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, rows.joinToString("\n") + "\n")
    }

    private fun rows(path: Path): List<String> = Files.readString(path).split("\n")

    private fun names(dir: Path): List<String> = Files.list(dir).use { files ->
        files.map { it.fileName.toString() }.sorted().toList()
    }

    // V4-259: the rewrite is a temp file beside the transcript, moved over it in one step. It wrote in
    // place, so a write that died partway left the user's transcript truncated.
    @Test
    fun `a write that dies partway leaves the transcript byte-identical and no temp file behind`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), USER_ROW, FOREIGN_ROW)
        val before = Files.readString(transcript)

        val dying = TranscriptModelRewrite(DyingWrite(kept = 10))
        assertThrows(IOException::class.java) { dying.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY) }

        assertEquals(before, Files.readString(transcript), "the user's transcript is exactly as it was")
        assertEquals(listOf("s1.jsonl"), names(dir), "no temp file is left beside it")
    }

    @Test
    fun `a swap that is refused leaves the transcript byte-identical and no temp file behind`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), USER_ROW, FOREIGN_ROW)
        val before = Files.readString(transcript)

        assertThrows(IOException::class.java) {
            TranscriptModelRewrite(RefusedSwap).rewrite(transcript, "gpt-5.6-sol", SOL_ONLY)
        }

        assertEquals(before, Files.readString(transcript), "the user's transcript is exactly as it was")
        assertEquals(listOf("s1.jsonl"), names(dir), "no temp file is left beside it")
    }

    @Test
    fun `a rewrite replaces the transcript whole, keeps its permissions and leaves no temp file`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), USER_ROW, FOREIGN_ROW)
        Files.setPosixFilePermissions(transcript, PosixFilePermissions.fromString("rw-r-----"))

        assertEquals(1, rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY))

        assertEquals(listOf(USER_ROW, FOREIGN_ROW_ON_SOL, ""), rows(transcript))
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(transcript)))
        assertEquals(listOf("s1.jsonl"), names(dir))
    }

    @Test
    fun `a transcript reached through a link is rewritten where the link points, and stays a link`(@TempDir dir: Path) {
        val real = write(dir.resolve("store").resolve("s1.jsonl"), FOREIGN_ROW)
        val link = Files.createSymbolicLink(dir.resolve("s1.jsonl"), real)

        assertEquals(1, rewriter.rewrite(link, "gpt-5.6-sol", SOL_ONLY))

        assertTrue(Files.isSymbolicLink(link), "the link is not replaced by a copy")
        assertEquals(FOREIGN_ROW_ON_SOL, rows(real)[0])
        assertEquals(listOf("s1.jsonl"), names(real.parent))
    }

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
        assertEquals(
            0,
            rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY),
            "idempotent: a second pass moves nothing",
        )
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

    // A head that never signs thinking (Kimi) has splice stamp `splice-synth-v1` at close so Claude
    // Code keeps the block. Retagged onto claude-splice's roster, the row claims to be Fable's, Claude
    // Code replays the block, and Anthropic answers 400 "Invalid signature in thinking block" on every
    // turn. The retag is the last moment the block is known to be foreign, so it leaves with the model.
    @Test
    fun `a foreign row loses its thinking with its model, and a served row keeps both`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), KIMI_THINKING_ROW, FABLE_THINKING_ROW)

        val rewritten = rewriter.rewrite(transcript, "claude-fable-5", listOf("claude-fable-5", "claude-opus-5"))

        assertEquals(1, rewritten)
        assertEquals(movedRow("a1", "u1", """[{"type":"text","text":"answer"}]"""), rows(transcript)[0])
        assertEquals(FABLE_THINKING_ROW, rows(transcript)[1], "a served row's signature is Anthropic's: byte-identical")
    }

    // Claude Code writes one row per content block, so a row can hold ONLY thinking. The row stays (its
    // uuid is the next row's parentUuid) and takes the block Claude Code 2.1.281 itself puts in a message
    // it strips bare while recovering from this same 400 (aEt/kcr in the binary), so the shape is one
    // Claude Code already loads, merges by message.id and replays.
    @Test
    fun `a foreign row that held only thinking keeps its place with Claude Code's own placeholder`(
        @TempDir dir: Path,
    ) {
        val thinkingOnly = """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_k",""" +
            """"model":"k3","content":[{"type":"thinking","thinking":"plan","signature":"splice-synth-v1"}]}}"""
        val redactedOnly = """{"type":"assistant","uuid":"a2","parentUuid":"a1","message":{"id":"msg_k",""" +
            """"model":"k3","content":[{"type":"redacted_thinking","data":"gAAAA"}]}}"""
        val transcript = write(dir.resolve("s1.jsonl"), thinkingOnly, redactedOnly)

        assertEquals(2, rewriter.rewrite(transcript, "claude-fable-5", listOf("claude-fable-5")))

        val placeholder = """[{"type":"text","text":"[Thinking removed]","citations":[]}]"""
        assertEquals(
            listOf(movedRow("a1", "u1", placeholder), movedRow("a2", "a1", placeholder), ""),
            rows(transcript),
            "no row is dropped: the parentUuid chain holds",
        )
    }

    /** A `msg_k` row as the rewrite leaves it on claude-fable-5, [content] being its content JSON. */
    private fun movedRow(uuid: String, parent: String, content: String): String =
        """{"type":"assistant","uuid":"$uuid","parentUuid":"$parent",""" +
            """"message":{"id":"msg_k","model":"claude-fable-5","content":$content}}"""

    @Test
    fun `a subagent transcript loses its foreign thinking too`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), USER_ROW)
        val subagent = write(dir.resolve("s1").resolve("subagents").resolve("agent-1.jsonl"), KIMI_THINKING_ROW)

        assertEquals(1, rewriter.rewrite(transcript, "claude-fable-5", listOf("claude-fable-5")))

        assertEquals(false, rows(subagent)[0].contains("thinking"), rows(subagent)[0])
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

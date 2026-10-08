// NEW: V4-169 — the one rewrite both resume moments share, pinned on its own: which rows move,
// which bytes stay, and that a failure is thrown rather than half-applied.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import java.io.ByteArrayOutputStream
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

/** About 300 MB of rows of about 1 MB each. */
private const val LARGE_ROWS = 300
private const val LARGE_ROW_CHARS = 1_000_000

/** The whole-file rewrite held the text, its rows, the moved rows, the joined text and the encoded
 *  bytes at once: about ten heap bytes per transcript byte once a row decodes two bytes a character. */
private const val WHOLE_FILE_HEAP_PER_BYTE = 8

/** A write that puts its first [kept] bytes down and then dies, as a crash or a full disk does. */
private class DyingWrite(private val kept: Int) : TranscriptFs {
    override fun write(path: Path, rows: StagedRows) {
        val bytes = ByteArrayOutputStream().also { rows(it) }.toByteArray()
        Files.write(path, bytes.copyOf(kept))
        throw IOException("No space left on device")
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        Files.move(source, target, *options)
    }
}

/** A write that lands whole, and a swap that is refused. */
private object RefusedSwap : TranscriptFs {
    override fun write(path: Path, rows: StagedRows) {
        Files.newOutputStream(path).use { rows(it) }
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        throw IOException("move refused")
    }
}

class TranscriptModelRewriteTest {

    @TempDir lateinit var state: Path

    private val originals get() = TranscriptOriginals(StatePaths(baseOverride = state))
    private val rewriter get() = TranscriptModelRewrite(originals = originals)

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

        val dying = TranscriptModelRewrite(DyingWrite(kept = 10), originals)
        assertThrows(IOException::class.java) { dying.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY) }

        assertEquals(before, Files.readString(transcript), "the user's transcript is exactly as it was")
        assertEquals(listOf("s1.jsonl"), names(dir), "no temp file is left beside it")
    }

    @Test
    fun `a swap that is refused leaves the transcript byte-identical and no temp file behind`(@TempDir dir: Path) {
        val transcript = write(dir.resolve("s1.jsonl"), USER_ROW, FOREIGN_ROW)
        val before = Files.readString(transcript)

        assertThrows(IOException::class.java) {
            TranscriptModelRewrite(RefusedSwap, originals).rewrite(transcript, "gpt-5.6-sol", SOL_ONLY)
        }

        assertEquals(before, Files.readString(transcript), "the user's transcript is exactly as it was")
        assertEquals(listOf("s1.jsonl"), names(dir), "no temp file is left beside it")
    }

    // Review of 9226c8eb9 (splice-reviewer): the staged bytes are whatever the staging read consumed, so that read
    // must be the surveyed bytes. A file that holds other bytes while it is staged, and is restored before the
    // last check, published the staged bytes read from the other ones.
    @Test
    fun `staged bytes come from the surveyed bytes, even when the file is restored before the last check`(
        @TempDir dir: Path,
    ) {
        val transcript = write(dir.resolve("s1.jsonl"), FOREIGN_ROW)
        val original = Files.readString(transcript)
        val transient = USER_ROW + "\n"
        val flipping = object : TranscriptFs {
            override fun write(path: Path, rows: StagedRows) {
                Files.writeString(transcript, transient)
                Files.newOutputStream(path).use { rows(it) }
                Files.writeString(transcript, original)
            }

            override fun move(source: Path, target: Path, vararg options: CopyOption) {
                Files.move(source, target, *options)
            }
        }

        assertThrows(IOException::class.java) {
            TranscriptModelRewrite(flipping, originals).rewrite(transcript, "gpt-5.6-sol", SOL_ONLY)
        }

        assertEquals(original, Files.readString(transcript), "the user's transcript is exactly as it was")
        assertEquals(listOf("s1.jsonl"), names(dir), "no temp file is left beside it")
    }

    // Review of 9226c8eb9 (splice-reviewer): a link retargeted while it is staged. The rewrite must not replace
    // the file the link names now with bytes read from the file it named before.
    @Test
    fun `a link retargeted during staging is refused, and the file it named before keeps its appended row`(
        @TempDir dir: Path,
    ) {
        val first = write(dir.resolve("first.jsonl"), FOREIGN_ROW)
        val other = write(dir.resolve("other.jsonl"), FOREIGN_ROW)
        val link = Files.createSymbolicLink(dir.resolve("s2.jsonl"), first)
        val appended = Files.readString(first) + USER_ROW + "\n"
        val retargeting = object : TranscriptFs {
            override fun write(path: Path, rows: StagedRows) {
                Files.newOutputStream(path).use { rows(it) }
                Files.writeString(first, appended)
                Files.delete(link)
                Files.createSymbolicLink(link, other)
            }

            override fun move(source: Path, target: Path, vararg options: CopyOption) {
                Files.move(source, target, *options)
            }
        }

        assertThrows(IOException::class.java) {
            TranscriptModelRewrite(retargeting, originals).rewrite(link, "gpt-5.6-sol", SOL_ONLY)
        }

        assertEquals(appended, Files.readString(first), "the row appended to the file it named is not erased")
        assertEquals(FOREIGN_ROW + "\n", Files.readString(other), "the file the link names now is not rewritten")
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

    // Oct 7 CT, reported by a peer seat: resuming a 715 MB and a 1022 MB transcript failed the whole launch
    // ("unclassified failure") while a 338 MB one resumed. The rewrite held each file whole, as text,
    // several copies at once, and the JDK's UTF-8 encode of text past about 715M characters throws
    // NegativeArraySizeException. This transcript is sized so a whole-file rewrite cannot fit the test
    // heap: the whole-file rewrite ran this JVM out of memory on it. A rewrite holds one row at a time.
    @Test
    fun `a transcript too large to hold in memory is rewritten one row at a time`(@TempDir dir: Path) {
        val heap = Runtime.getRuntime().maxMemory()
        val size = LARGE_ROWS.toLong() * LARGE_ROW_CHARS
        assertTrue(heap < size * WHOLE_FILE_HEAP_PER_BYTE, "a $heap-byte heap could hold this transcript whole")
        // One non-Latin-1 character, as real transcripts carry, makes every decoded row two bytes a character.
        val text = "✓" + "x".repeat(LARGE_ROW_CHARS)
        val transcript = dir.resolve("s1.jsonl")
        Files.newBufferedWriter(transcript).use { out ->
            repeat(LARGE_ROWS) { out.write(largeRow("k3-256k", text) + "\n") }
        }
        val before = Files.size(transcript)

        assertEquals(LARGE_ROWS, rewriter.rewrite(transcript, "gpt-5.6-sol", SOL_ONLY))

        val grown = ("gpt-5.6-sol".length - "k3-256k".length).toLong() * LARGE_ROWS
        assertEquals(before + grown, Files.size(transcript), "only each row's model changed")
        val moved = largeRow("gpt-5.6-sol", text)
        Files.newBufferedReader(transcript).use { rows ->
            assertEquals(LARGE_ROWS, rows.lineSequence().count { it == moved })
        }
    }

    private fun largeRow(model: String, text: String): String =
        """{"type":"assistant","sessionId":"s1","message":{"model":"$model",""" +
            """"content":[{"type":"text","text":"$text"}]}}"""

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

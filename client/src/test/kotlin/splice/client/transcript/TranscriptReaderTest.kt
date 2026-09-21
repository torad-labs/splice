// NEW: V4-130 — TranscriptReader reads a session's transcript as a conversation, a page at a time.
// The fixture carries every record shape the reader has a rule for, in the order a real transcript
// writes them (one line per content block, several lines sharing one message.id), so each rule is
// asserted on the message list it produces, and the skipped counts are asserted EXACTLY: they are the
// page's denominator.
//
// WHERE: the root order (own head tree, vanilla, other heads' trees) is exercised through all three
// branches, including the other-heads fallback, which is the common case for the nine heads that keep
// their own tree, and a root whose projects dir is a symlink to the vanilla one.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

private const val ID = "3f2a9c1e-0000-4000-8000-000000000001"
private const val TS = "2026-09-18T10:00:00.000Z"
private const val TS_MS = 1_789_725_600_000L

/** One record per line, in the order Claude Code writes them. */
private val FIXTURE = listOf(
    """{"type":"user","timestamp":"$TS","message":{"role":"user","content":"read the config"}}""",
    """{"type":"attachment","attachment":{"type":"file"}}""",
    """{"type":"assistant","timestamp":"$TS","message":{"id":"msg_1","role":"assistant","content":[{"type":"thinking","thinking":"private"}]}}""",
    """{"type":"assistant","timestamp":"$TS","message":{"id":"msg_1","role":"assistant","content":[{"type":"text","text":"Reading it now."}]}}""",
    """{"type":"assistant","timestamp":"$TS","message":{"id":"msg_1","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"/w/splice.toml"}}]}}""",
    """{"type":"user","timestamp":"$TS","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"api_key = sk-abcdefghijklmnopqrstuvwxyz"}]}}""",
    """{this line is not json""",
    """{"type":"assistant","isSidechain":true,"message":{"id":"msg_side","role":"assistant","content":[{"type":"text","text":"subagent"}]}}""",
    """{"type":"system","subtype":"turn_duration","durationMs":12}""",
    """{"type":"user","isMeta":true,"message":{"role":"user","content":"<command-name>/model</command-name>"}}""",
    """{"type":"assistant","isApiErrorMessage":true,"message":{"id":"msg_err","role":"assistant","content":[{"type":"text","text":"overloaded_error"}]}}""",
    """{"type":"assistant","message":{"id":"msg_2","role":"assistant","content":[{"type":"text","text":"Done."}]}}""",
)

class TranscriptReaderTest {

    @TempDir
    lateinit var home: Path

    /** Writes the fixture as [root]/projects/<slug>/<ID>.jsonl and returns the file. */
    private fun transcript(root: Path, lines: List<String> = FIXTURE): Path {
        val file = root.resolve("projects/-w-repo/$ID.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun found(lookup: TranscriptLookup): TranscriptPage {
        assertTrue(lookup is TranscriptLookup.Found, lookup.toString())
        return (lookup as TranscriptLookup.Found).page
    }

    @Test
    fun `records fold into the conversation and everything else is counted by kind`() {
        val file = transcript(home.resolve(".claude"))
        val page = found(TranscriptReader { listOf(home.resolve(".claude")) }.page(ID, null, null, 100))
        assertEquals(file.toString(), page.path)
        assertEquals(
            listOf(
                TranscriptMessage(0, TranscriptRole.USER, TS_MS, "read the config"),
                TranscriptMessage(1, TranscriptRole.ASSISTANT, TS_MS, "Reading it now."),
                TranscriptMessage(
                    2,
                    TranscriptRole.ASSISTANT,
                    TS_MS,
                    """{"file_path":"/w/splice.toml"}""",
                    "Read",
                    false,
                ),
                TranscriptMessage(3, TranscriptRole.TOOL, TS_MS, "api_key = [redacted]", "Read", true),
                TranscriptMessage(4, TranscriptRole.SYSTEM, null, "<command-name>/model</command-name>"),
                TranscriptMessage(5, TranscriptRole.SYSTEM, null, "API error: overloaded_error"),
                TranscriptMessage(6, TranscriptRole.ASSISTANT, null, "Done."),
            ),
            page.messages,
        )
        assertEquals(
            mapOf("attachment" to 1, "sidechain" to 1, "system:turn_duration" to 1, "unparseable" to 1),
            page.skipped,
        )
        assertNull(page.next, "the whole file fit in one page")
    }

    @Test
    fun `pages continue where the last one stopped and never split a message`() {
        transcript(home.resolve(".claude"))
        val reader = TranscriptReader { listOf(home.resolve(".claude")) }
        val first = found(reader.page(ID, null, null, 2))
        // msg_1's three lines are one message group: its text AND its tool call land on this page.
        assertEquals(listOf(0L, 1L, 2L), first.messages.map { it.index })
        val second = found(reader.page(ID, null, first.next, 2))
        assertEquals(listOf(3L, 4L), second.messages.map { it.index })
        val rest = generateSequence(second) { page -> page.next?.let { found(reader.page(ID, null, it, 2)) } }.toList()
        assertEquals((0L..6L).toList(), (first.messages + rest.flatMap { it.messages }).map { it.index })
        assertEquals(4, rest.sumOf { it.skipped.values.sum() } + first.skipped.values.sum(), "each skip counted once")
    }

    @Test
    fun `the head's own tree wins, the vanilla tree follows, and other heads' trees are searched last`() {
        val own = home.resolve(".claude-own")
        val vanilla = home.resolve(".claude")
        val other = home.resolve(".claude-other")
        val trees = TranscriptTrees { head -> listOfNotNull(own.takeIf { head == "own" }, vanilla, other) }
        val reader = TranscriptReader(trees)
        val otherFile = transcript(other)
        assertEquals(otherFile.toString(), found(reader.page(ID, null, null, 1)).path, "the other-heads fallback")
        val vanillaFile = transcript(vanilla)
        assertEquals(vanillaFile.toString(), found(reader.page(ID, null, null, 1)).path)
        val ownFile = transcript(own)
        assertEquals(ownFile.toString(), found(reader.page(ID, "own", null, 1)).path, "the live client's copy wins")
    }

    @Test
    fun `a head whose projects dir is a symlink to the vanilla tree finds the vanilla file`() {
        val vanilla = home.resolve(".claude")
        val file = transcript(vanilla)
        val linked = Files.createDirectories(home.resolve(".claude-linked"))
        Files.createSymbolicLink(linked.resolve("projects"), vanilla.resolve("projects"))
        val page = found(TranscriptReader { listOf(linked, vanilla) }.page(ID, null, null, 1))
        val named = linked.resolve("projects").resolve(file.parent.fileName).resolve(file.fileName)
        assertEquals(named.toString(), page.path)
    }

    @Test
    fun `a miss names every projects dir searched, and a bad id or cursor is refused`() {
        val reader = TranscriptReader { listOf(home.resolve(".claude"), home.resolve(".claude-x")) }
        assertEquals(
            TranscriptLookup.Missing(
                listOf(home.resolve(".claude/projects").toString(), home.resolve(".claude-x/projects").toString()),
            ),
            reader.page(ID, null, null, 1),
        )
        assertTrue(reader.page("../etc", null, null, 1) is TranscriptLookup.Refused)
        assertTrue(reader.page(ID, null, "1.2.3", 1) is TranscriptLookup.Refused)
        assertTrue(reader.page(ID, null, "-1.0", 1) is TranscriptLookup.Refused)
    }
}

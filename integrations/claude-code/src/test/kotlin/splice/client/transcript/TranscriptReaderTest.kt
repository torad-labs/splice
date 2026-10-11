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
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import splice.sessions.transcript.TranscriptToolUse
import java.nio.file.Files
import java.nio.file.Path

private const val ID = "3f2a9c1e-0000-4000-8000-000000000001"
private const val TS = "2026-09-18T10:00:00.000Z"
private const val TS_MS = 1_789_725_600_000L

/** One record per line, in the order Claude Code writes them. */
private val FIXTURE = listOf(
    """{"type":"user","timestamp":"$TS","message":{"role":"user","content":"read the config"}}""",
    """{"type":"attachment","attachment":{"type":"file"}}""",
    """{"type":"assistant","timestamp":"$TS","message":{"id":"msg_1","role":"assistant","content":[""" +
        """{"type":"thinking","thinking":"private"}]}}""",
    """{"type":"assistant","timestamp":"$TS","message":{"id":"msg_1","role":"assistant","content":[""" +
        """{"type":"text","text":"Reading it now."}]}}""",
    """{"type":"assistant","timestamp":"$TS","message":{"id":"msg_1","role":"assistant","content":[""" +
        """{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"/w/splice.toml"}}]}}""",
    """{"type":"user","timestamp":"$TS","message":{"role":"user","content":[""" +
        """{"type":"tool_result","tool_use_id":"toolu_1","content":"api_key = sk-abcdefghijklmnopqrstuvwxyz"}]}}""",
    """{this line is not json""",
    """{"type":"assistant","isSidechain":true,"message":{"id":"msg_side","role":"assistant","content":[""" +
        """{"type":"text","text":"subagent"}]}}""",
    """{"type":"system","subtype":"turn_duration","durationMs":12}""",
    """{"type":"user","isMeta":true,"message":{"role":"user","content":"<command-name>/model</command-name>"}}""",
    """{"type":"assistant","isApiErrorMessage":true,"message":{"id":"msg_err","role":"assistant","content":[""" +
        """{"type":"text","text":"overloaded_error"}]}}""",
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
        val page = found(TranscriptReader().page(ID, listOf(home.resolve(".claude")), null, 100))
        assertEquals(file.toString(), page.path)
        assertEquals(
            listOf(
                TranscriptMessage(0, TranscriptRole.USER, TS_MS, "read the config"),
                TranscriptMessage(1, TranscriptRole.ASSISTANT, TS_MS, "Reading it now.", messageId = "msg_1"),
                TranscriptMessage(
                    2,
                    TranscriptRole.ASSISTANT,
                    TS_MS,
                    """{"file_path":"/w/splice.toml"}""",
                    TranscriptToolUse("Read", false, "toolu_1"),
                    messageId = "msg_1",
                ),
                TranscriptMessage(
                    3,
                    TranscriptRole.TOOL,
                    TS_MS,
                    "api_key = [redacted]",
                    TranscriptToolUse("Read", true, "toolu_1"),
                ),
                TranscriptMessage(4, TranscriptRole.SYSTEM, null, "<command-name>/model</command-name>"),
                TranscriptMessage(5, TranscriptRole.SYSTEM, null, "API error: overloaded_error"),
                TranscriptMessage(6, TranscriptRole.ASSISTANT, null, "Done.", messageId = "msg_2"),
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
    fun `an API error already worded as one by the client is not prefixed twice`() {
        val worded = """{"type":"assistant","isApiErrorMessage":true,"message":{"id":"msg_e","role":"assistant",""" +
            """"content":[{"type":"text","text":"API Error: Request rejected (429)"}]}}"""
        transcript(home.resolve(".claude"), listOf(worded))
        val page = found(TranscriptReader().page(ID, listOf(home.resolve(".claude")), null, 100))
        assertEquals(listOf("API Error: Request rejected (429)"), page.messages.map { it.text })
    }

    @Test
    fun `pages continue where the last one stopped and never split a message`() {
        transcript(home.resolve(".claude"))
        val reader = TranscriptReader()
        val roots = listOf(home.resolve(".claude"))
        val first = found(reader.page(ID, roots, null, 2))
        // msg_1's three lines are one message group: its text AND its tool call land on this page.
        assertEquals(listOf(0L, 1L, 2L), first.messages.map { it.index })
        val second = found(reader.page(ID, roots, first.next, 2))
        assertEquals(listOf(3L, 4L), second.messages.map { it.index })
        val rest = generateSequence(second) { page -> page.next?.let { found(reader.page(ID, roots, it, 2)) } }.toList()
        assertEquals((0L..6L).toList(), (first.messages + rest.flatMap { it.messages }).map { it.index })
        assertEquals(4, rest.sumOf { it.skipped.values.sum() } + first.skipped.values.sum(), "each skip counted once")
    }

    @Test
    fun `tool ids survive forward pages newest pages and indexed response context`() {
        val root = home.resolve(".claude")
        val lines = listOf(
            """{"type":"assistant","message":{"id":"calls","content":[""" +
                """{"type":"tool_use","id":"first","name":"Read","input":{"file_path":"/synthetic/first"}},""" +
                """{"type":"tool_use","id":"second","name":"Read","input":{"file_path":"/synthetic/second"}}]}}""",
            """{"type":"user","message":{"content":[""" +
                """{"type":"tool_result","tool_use_id":"first","content":"first body"},""" +
                """{"type":"tool_result","tool_use_id":"second","content":"second body"}]}}""",
            """{"type":"assistant","message":{"id":"answer","content":[{"type":"text","text":"done"}]}}""",
        )
        transcript(root, lines)
        val reader = TranscriptReader()
        val roots = listOf(root)
        val first = found(reader.page(ID, roots, null, 1))
        assertEquals(listOf("first", "second"), first.messages.map { it.toolUse.id })
        val results = found(reader.page(ID, roots, first.next, 100)).messages
        assertEquals(listOf("first", "second"), results.filter { it.toolUse.result == true }.map { it.toolUse.id })
        assertTrue(
            results.filter { it.toolUse.result == true }.all { it.toolUse.name == null },
            "ids survive without a call on this page",
        )
        val whole = found(reader.page(ID, roots, null, 100)).messages
        val newest = found(reader.pageBefore(ID, roots, null, 100)).messages
        assertEquals(whole.map { it.toolUse.id }, newest.map { it.toolUse.id })
        val selected = reader.response(ID, roots, "answer", 100) { true }
        assertTrue(selected is splice.sessions.transcript.MessageConversation.Found)
        selected as splice.sessions.transcript.MessageConversation.Found
        assertEquals(whole.map { it.toolUse.id }, selected.messages.map { it.toolUse.id })
        assertEquals(0L, selected.earlier)
    }

    @Test
    fun `the head's own tree wins, the vanilla tree follows, and other heads' trees are searched last`() {
        val own = home.resolve(".claude-own")
        val vanilla = home.resolve(".claude")
        val other = home.resolve(".claude-other")
        // The caller's order: a headless session searches vanilla then the other heads; a headed one
        // puts its own tree first.
        val headless = listOf(vanilla, other)
        val headed = listOf(own, vanilla, other)
        val reader = TranscriptReader()
        val otherFile = transcript(other)
        assertEquals(otherFile.toString(), found(reader.page(ID, headless, null, 1)).path, "the other-heads fallback")
        val vanillaFile = transcript(vanilla)
        assertEquals(vanillaFile.toString(), found(reader.page(ID, headless, null, 1)).path)
        val ownFile = transcript(own)
        assertEquals(ownFile.toString(), found(reader.page(ID, headed, null, 1)).path, "the live client's copy wins")
    }

    @Test
    fun `a head whose projects dir is a symlink to the vanilla tree finds the vanilla file`() {
        val vanilla = home.resolve(".claude")
        val file = transcript(vanilla)
        val linked = Files.createDirectories(home.resolve(".claude-linked"))
        Files.createSymbolicLink(linked.resolve("projects"), vanilla.resolve("projects"))
        val page = found(TranscriptReader().page(ID, listOf(linked, vanilla), null, 1))
        val named = linked.resolve("projects").resolve(file.parent.fileName).resolve(file.fileName)
        assertEquals(named.toString(), page.path)
    }

    @Test
    fun `a miss names every projects dir searched, and a bad id or cursor is refused`() {
        val reader = TranscriptReader()
        val roots = listOf(home.resolve(".claude"), home.resolve(".claude-x"))
        assertEquals(
            TranscriptLookup.Missing(
                listOf(home.resolve(".claude/projects").toString(), home.resolve(".claude-x/projects").toString()),
            ),
            reader.page(ID, roots, null, 1),
        )
        assertTrue(reader.page("../etc", roots, null, 1) is TranscriptLookup.Refused)
        assertTrue(reader.page(ID, roots, "1.2.3", 1) is TranscriptLookup.Refused)
        assertTrue(reader.page(ID, roots, "-1.0", 1) is TranscriptLookup.Refused)
    }

    /** The newest messages first, then each earlier page, as [TranscriptReader.pageBefore] hands them out. */
    private fun backwards(reader: TranscriptReader, roots: List<Path>, limit: Int): List<TranscriptPage> =
        generateSequence(found(reader.pageBefore(ID, roots, null, limit))) { page ->
            page.earlier?.let { found(reader.pageBefore(ID, roots, it, limit)) }
        }.toList()

    @Test
    fun `the newest messages come first as a page, and each earlier page continues from it`() {
        transcript(home.resolve(".claude"))
        val reader = TranscriptReader()
        val roots = listOf(home.resolve(".claude"))
        val forward = found(reader.page(ID, roots, null, 100)).messages.map { it.role to it.text }
        val pages = backwards(reader, roots, 3)
        // the newest page holds the newest three, oldest first inside it
        assertEquals(forward.takeLast(3), pages.first().messages.map { it.role to it.text })
        // stitched from the oldest page forward, the pages are the conversation once
        assertEquals(forward, pages.reversed().flatMap { it.messages }.map { it.role to it.text })
        assertNull(pages.last().earlier, "the origin has nothing earlier")
        assertTrue(pages.dropLast(1).all { it.earlier != null })
    }

    @Test
    fun `an earlier page never splits an assistant message whose blocks are several lines`() {
        transcript(home.resolve(".claude"))
        val reader = TranscriptReader()
        val roots = listOf(home.resolve(".claude"))
        // limit 5 would cut between msg_1's text and its call: the page takes the whole message instead
        val pages = backwards(reader, roots, 5)
        assertEquals(listOf(6, 1), pages.map { it.messages.size })
        val firstTwo = pages.first().messages.take(2).map { it.text }
        assertEquals(listOf("Reading it now.", """{"file_path":"/w/splice.toml"}"""), firstTwo)
    }

    @Test
    fun `message indices are unique and rise from the oldest page to the newest`() {
        transcript(home.resolve(".claude"))
        val pages = backwards(TranscriptReader(), listOf(home.resolve(".claude")), 2)
        val indices = pages.reversed().flatMap { it.messages }.map { it.index }
        assertEquals(indices.sorted(), indices)
        assertEquals(indices.size, indices.toSet().size)
    }

    @Test
    fun `a limit past the conversation returns all of it with nothing earlier, and a bad cursor is refused`() {
        transcript(home.resolve(".claude"))
        val reader = TranscriptReader()
        val roots = listOf(home.resolve(".claude"))
        val all = found(reader.pageBefore(ID, roots, null, 100))
        assertEquals(7, all.messages.size)
        assertNull(all.earlier)
        assertTrue(reader.pageBefore(ID, roots, "x", 3) is TranscriptLookup.Refused)
        val past = reader.pageBefore(ID, roots, "99999999", 3)
        assertTrue(past is TranscriptLookup.Refused, "past the end of the file")
    }

    @Test
    fun `an earlier page across a window boundary gives the same messages as reading the whole file`() {
        // 300 user lines of 1 KiB: the newest page has to read several 64 KiB windows back to find its 200.
        val pad = "x".repeat(1024)
        val lines = (0 until 300).map { n -> """{"type":"user","message":{"role":"user","content":"m$n $pad"}}""" }
        transcript(home.resolve(".claude"), lines)
        val reader = TranscriptReader()
        val roots = listOf(home.resolve(".claude"))
        val pages = backwards(reader, roots, 200)
        assertEquals(listOf(200, 100), pages.map { it.messages.size })
        val texts = pages.reversed().flatMap { it.messages }.map { it.text.substringBefore(' ') }
        assertEquals((0 until 300).map { "m$it" }, texts)
    }
}

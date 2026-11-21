// NEW: rv-transcript — a reply's thinking rides on the first message it made, on whichever page holds that message.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION_ID = "3f2a9c1e-0000-4000-8000-0000000000bb"

private fun reply(id: String, block: String): String =
    """{"type":"assistant","message":{"id":"$id","role":"assistant","model":"claude-opus-5-5","content":[$block]}}"""

/** A person's message, a reply that thinks, says something and calls a tool, its result, and the next message. */
private val SPLIT = listOf(
    """{"type":"user","message":{"role":"user","content":"look at the parser"}}""",
    reply("msg_a", """{"type":"thinking","thinking":"The parser skips a column.","signature":"sig-made-up"}"""),
    reply("msg_a", """{"type":"text","text":"Reading it."}"""),
    reply("msg_a", """{"type":"tool_use","id":"toolu_a","name":"Read","input":{"file_path":"/w/p.ts"}}"""),
    """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_a",""" +
        """"content":"export {}"}]}}""",
    """{"type":"user","message":{"role":"user","content":"thanks"}}""",
)

class TranscriptThinkingTest {

    @TempDir
    lateinit var home: Path

    private val roots: List<Path> get() = listOf(home)

    private fun write(lines: List<String>) {
        val file = home.resolve("projects/-w-repo/$SESSION_ID.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
    }

    private fun found(lookup: TranscriptLookup): TranscriptPage {
        assertTrue(lookup is TranscriptLookup.Found, lookup.toString())
        return (lookup as TranscriptLookup.Found).page
    }

    private fun forwardPages(limit: Int): List<TranscriptPage> {
        val reader = TranscriptReader()
        return generateSequence(found(reader.page(SESSION_ID, roots, null, limit))) { page ->
            page.next?.let { found(reader.page(SESSION_ID, roots, it, limit)) }
        }.toList()
    }

    private fun backPages(limit: Int): List<TranscriptPage> {
        val reader = TranscriptReader()
        return generateSequence(found(reader.pageBefore(SESSION_ID, roots, null, limit))) { page ->
            page.earlier?.let { found(reader.pageBefore(SESSION_ID, roots, it, limit)) }
        }.toList()
    }

    /** Every page that holds any of msg_a holds all of it, and its thinking is on its first message only. */
    private fun assertThinkingOnFirst(pages: List<TranscriptPage>) {
        val holding = pages.filter { page -> page.messages.any { it.messageId == "msg_a" } }
        assertEquals(1, holding.size, "the reply is on one page")
        val replyMessages = holding.single().messages.filter { it.messageId == "msg_a" }
        assertEquals(listOf("Reading it.", """{"file_path":"/w/p.ts"}"""), replyMessages.map { it.text })
        assertEquals(listOf("The parser skips a column.", null), replyMessages.map { it.source.thinking })
        assertEquals(listOf("claude-opus-5-5", "claude-opus-5-5"), replyMessages.map { it.source.model })
        val all = pages.flatMap { it.messages }
        assertFalse(all.any { "skips a column" in it.text }, "thinking is never a message's text")
        assertFalse(all.any { it.source.thinking.orEmpty().contains("sig-made-up") })
    }

    @Test
    fun `a reply cut by a forward page limit keeps its thinking on its first message`() {
        write(SPLIT)
        for (limit in 1..3) assertThinkingOnFirst(forwardPages(limit))
    }

    @Test
    fun `a reply cut by a page read from the end keeps its thinking on its first message`() {
        write(SPLIT)
        for (limit in 1..3) assertThinkingOnFirst(backPages(limit))
    }

    @Test
    fun `a reply after a message read again carries its thinking`() {
        write(SPLIT)
        val reader = TranscriptReader()
        val newest = found(reader.pageBefore(SESSION_ID, roots, null, 100)).messages
        val said = newest.first { it.text == "Reading it." }
        val again = found(reader.pageAfter(SESSION_ID, roots, said.index, 100)).messages
        assertEquals("The parser skips a column.", again.first { it.text == "Reading it." }.source.thinking)
    }

    @Test
    fun `a reply of only thinking and calls carries its thinking on its first call, redacted, blocks joined`() {
        write(
            listOf(
                reply("msg_b", """{"type":"thinking","thinking":"First, token=abcdefghijklmnop."}"""),
                reply("msg_b", """{"type":"redacted_thinking","data":"opaque-made-up"}"""),
                reply("msg_b", """{"type":"thinking","thinking":"[Thinking removed]"}"""),
                reply("msg_b", """{"type":"thinking","thinking":"Then the map."}"""),
                reply("msg_b", """{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}"""),
                reply("msg_b", """{"type":"tool_use","id":"toolu_2","name":"Grep","input":{}}"""),
            ),
        )
        val messages: List<TranscriptMessage> = found(TranscriptReader().page(SESSION_ID, roots, null, 100)).messages
        assertEquals(listOf("Read", "Grep"), messages.map { it.toolUse.name })
        assertEquals(listOf("First, token=[redacted]\n\nThen the map.", null), messages.map { it.source.thinking })
    }

    @Test
    fun `a compaction summary is a marked system line and nothing else is marked`() {
        write(
            listOf(
                """{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"Summary: made up."}}""",
                """{"type":"user","isMeta":true,"message":{"role":"user","content":"a meta note"}}""",
                """{"type":"user","message":{"role":"user","content":"go on"}}""",
            ),
        )
        val messages = found(TranscriptReader().page(SESSION_ID, roots, null, 100)).messages
        assertEquals(listOf(true, false, false), messages.map { it.source.compact })
        assertEquals("Summary: made up.", messages.first().text)
    }
}

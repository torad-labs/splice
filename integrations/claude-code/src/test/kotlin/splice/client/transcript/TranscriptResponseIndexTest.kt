// Synthetic index regressions: bounded reads, continuation, rewrite, and complete selected groups.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptReadBudget
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

internal class CountingTranscriptOpener : TranscriptOpener {
    var bytes: Long = 0L

    override fun open(file: Path, offset: Long): InputStream = object : FilterInputStream(
        Channels.newInputStream(Files.newByteChannel(file, StandardOpenOption.READ).position(offset)),
    ) {
        override fun read(): Int = super.read().also { if (it >= 0) bytes++ }

        override fun read(buffer: ByteArray, start: Int, count: Int): Int =
            super.read(buffer, start, count).also { if (it > 0) bytes += it }
    }
}

private const val INDEX_SESSION = "synthetic-index-session"
private const val INDEX_REPLY = "msg_synthetic_selected"

class TranscriptResponseIndexTest {
    private fun reply(text: String, id: String = INDEX_REPLY): String =
        """{"type":"assistant","message":{"id":"$id","content":[{"type":"text","text":"$text"}]}}"""

    private fun file(root: Path, lines: List<String>): Path {
        val file = root.resolve("projects/synthetic/$INDEX_SESSION.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun found(
        lookup: TranscriptMessageLookup,
        root: Path,
        id: String = INDEX_REPLY,
    ): MessageConversation.Found =
        lookup.lookup(INDEX_SESSION, listOf(root), id) as MessageConversation.Found

    @Test
    fun `a selected reply larger than the backward byte budget is unavailable not a partial answer`(
        @TempDir root: Path,
    ) {
        file(root, listOf(reply("a".repeat(9 shl 20)), reply("b".repeat(9 shl 20))))
        val answer = TranscriptMessageLookup().lookup(INDEX_SESSION, listOf(root), INDEX_REPLY)
        assertTrue(
            answer is MessageConversation.Unavailable,
            "the second block alone is not the complete selected reply",
        )
    }

    @Test
    fun `append extends the last reply and then serves the next reply without duplicating old counts`(
        @TempDir root: Path,
    ) {
        val file = file(root, listOf(reply("older", "msg_old"), reply("first")))
        val lookup = TranscriptMessageLookup()
        assertEquals("first", found(lookup, root).messages.last().text)
        Files.writeString(file, reply("second") + "\n", StandardOpenOption.APPEND)
        assertEquals("first\n\nsecond", found(lookup, root).messages.last().text)
        Files.writeString(file, reply("new answer", "msg_new") + "\n", StandardOpenOption.APPEND)
        val next = found(lookup, root, "msg_new")
        assertEquals(listOf("older", "first\n\nsecond", "new answer"), next.messages.map { it.text })
        assertEquals(0L, next.earlier)
        assertEquals("first\n\nsecond", found(lookup, root).messages.last().text)
    }

    @Test
    fun `a cached response is discarded after a same-size rewrite and after truncate then grow`(@TempDir root: Path) {
        val file = file(root, listOf(reply("first")))
        val lookup = TranscriptMessageLookup()
        assertEquals("first", found(lookup, root).messages.last().text)
        val modified = Files.getLastModifiedTime(file).toMillis()
        Files.writeString(file, reply("other") + "\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified + 1_000L))
        assertEquals("other", found(lookup, root).messages.last().text)
        Files.writeString(file, reply("new history", "msg_old") + "\n" + reply("rewritten answer") + "\n")
        val rewritten = found(lookup, root)
        assertEquals(listOf("new history", "rewritten answer"), rewritten.messages.map { it.text })
    }

    @Test
    fun `escaped fields match while nested ids and sidechains cannot select a reply`(@TempDir root: Path) {
        file(
            root,
            listOf(
                """{"type":"assistant","isSidechain":true,"message":{"id":"$INDEX_REPLY","content":[{"type":"text","text":"hidden"}]}}""",
                """{"type":"assistant","message":{"id":"msg_other","content":[{"type":"tool_use","name":"synthetic","input":{"id":"$INDEX_REPLY"}}]}}""",
                """{"type":"assistant","message":{"id":"$INDEX_REPLY","content":[{"type":"text","text":"real reply"}]}}""",
                """{"type":"user","message":{"content":"later prompt"}}""",
            ),
        )
        val answer = found(TranscriptMessageLookup(), root)
        assertEquals("real reply", answer.messages.last().text)
        assertFalse(answer.messages.any { it.text == "hidden" || it.text == "later prompt" })
    }

    @Test
    fun `truncated metadata is skipped and a late duplicate null id cannot reuse the earlier id`(@TempDir root: Path) {
        file(
            root,
            listOf(
                """{"type":"assistant","message": """,
                """{"type":"assistant","message":{"id":"$INDEX_REPLY","id":null,"content":[{"type":"text","text":"not selected"}]}}""",
                reply("real reply"),
            ),
        )
        val lookup = TranscriptMessageLookup(TranscriptReader())
        val answer = found(lookup, root)
        assertEquals("real reply", answer.messages.last().text)
        assertEquals(listOf("not selected", "real reply"), answer.messages.map { it.text })
    }

    @Test
    fun `skipped records cannot truncate a contiguous reply or its later appended blocks`(@TempDir root: Path) {
        val file = file(root, listOf(reply("first"), """{"type":"bookkeeping"}""", reply("second")))
        val lookup = TranscriptMessageLookup()
        assertEquals("first\n\nsecond", found(lookup, root).messages.single().text)
        Files.writeString(file, """{"type":"bookkeeping"}""" + "\n" + reply("third") + "\n", StandardOpenOption.APPEND)
        assertEquals("first\n\nsecond\n\nthird", found(lookup, root).messages.single().text)
        Files.writeString(
            file,
            """{"type":"user","message":{"content":"later prompt"}}""" + "\n" + reply("later duplicate") + "\n",
            StandardOpenOption.APPEND,
        )
        assertEquals("first\n\nsecond\n\nthird", found(lookup, root).messages.single().text)
    }

    @Test
    fun `a spent metadata budget reports unavailable and cannot publish a partial cached index`(@TempDir root: Path) {
        file(root, List(20) { reply("history", "msg_history_$it") } + reply("selected"))
        val reader = TranscriptReader()
        var checks = 0
        val spent = reader.response(
            INDEX_SESSION,
            listOf(root),
            INDEX_REPLY,
            100,
            TranscriptReadBudget { ++checks < 4 },
        )
        assertTrue(spent is MessageConversation.Unavailable)
        assertEquals("selected", found(TranscriptMessageLookup(reader), root).messages.last().text)
    }

    @Test
    fun `eviction cannot make a later duplicate replace the first contiguous reply`(@TempDir root: Path) {
        file(root, listOf(reply("first")) + List(65_540) { reply("history", "msg_history_$it") } + reply("duplicate"))
        val lookup = TranscriptMessageLookup()
        assertEquals("first", found(lookup, root).messages.last().text)
        assertEquals("first", found(lookup, root).messages.last().text)
    }

    @Test
    fun `metadata counts and context match forward pages across record shapes`(@TempDir root: Path) {
        val shapes = listOf(
            """{"type":"user","message":{"content":"prompt"}}""",
            """{"type":"user","message":{"content":[{"type":"text","text":"text"},{"type":"tool_result","content":"result"}]}}""",
            """{"type":"system","content":"notice"}""",
            """{"type":"system","content":" "}""",
            """{"type":"assistant","message":{"id":"msg_hist","content":[{"type":"thinking","thinking":"private"}]}}""",
            """{"type":"assistant","message":{"id":"msg_hist","content":[{"type":"text","text":42},{"type":"tool_use","name":"synthetic","input":{}}]}}""",
            """{"type":"assistant","isApiErrorMessage":true,"message":{"content":[{"type":"text","text":"failed"}]}}""",
            """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"excluded"}]}}""",
            """{"type":"bookkeeping","message":{"id":"$INDEX_REPLY","content":"not a reply"}}""",
            "not JSON",
        )
        file(root, List(200) { shapes[it % shapes.size] } + reply("selected"))
        val reader = TranscriptReader()
        val forward = object : SessionTranscripts by reader {
            override fun response(
                sessionId: String,
                roots: List<Path>,
                responseId: String,
                context: Int,
                budget: TranscriptReadBudget,
            ): MessageConversation? = null // Deliberately exercise the existing forward-only port contract as the oracle.
        }
        val expected = found(TranscriptMessageLookup(forward), root)
        val actual = found(TranscriptMessageLookup(reader), root)
        assertEquals(expected.earlier, actual.earlier)
        assertEquals(expected.messages.map { it.copy(index = 0L) }, actual.messages.map { it.copy(index = 0L) })
    }
}

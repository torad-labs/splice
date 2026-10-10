// NEW: Oct 10, 2026 — the stand-in a move writes for a row it strips bare is plumbing, not the agent's words.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION_ID = "3f2a9c1e-0000-4000-8000-0000000000aa"

class TranscriptStandInTest {

    @TempDir
    lateinit var home: Path

    private fun texts(vararg lines: String): List<Pair<TranscriptRole, String>> {
        val file = home.resolve("projects/-w-repo/$SESSION_ID.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        val found = TranscriptReader().page(SESSION_ID, listOf(home), null, 100)
        assertTrue(found is TranscriptLookup.Found, found.toString())
        return (found as TranscriptLookup.Found).page.messages.map { it.role to it.text }
    }

    @Test
    fun `a row a move left holding only the stand-in says nothing`() {
        val said = texts(
            """{"type":"user","message":{"role":"user","content":"go"}}""",
            """{"type":"assistant","message":{"id":"m1","role":"assistant","content":[""" +
                """{"type":"text","text":"[Thinking removed]","citations":[]}]}}""",
            """{"type":"assistant","message":{"id":"m2","role":"assistant","content":[""" +
                """{"type":"text","text":"Done."}]}}""",
        )
        assertEquals(listOf(TranscriptRole.USER to "go", TranscriptRole.ASSISTANT to "Done."), said)
    }

    @Test
    fun `the stand-in beside real words is dropped and the words stay`() {
        val said = texts(
            """{"type":"assistant","message":{"id":"m1","role":"assistant","content":[""" +
                """{"type":"text","text":"[Thinking removed]"},{"type":"text","text":"Here it is."}]}}""",
        )
        assertEquals(listOf(TranscriptRole.ASSISTANT to "Here it is."), said)
    }

    @Test
    fun `an agent that quotes the phrase inside a longer sentence is still quoted`() {
        val said = texts(
            """{"type":"assistant","message":{"id":"m1","role":"assistant","content":[""" +
                """{"type":"text","text":"The row says [Thinking removed] there."}]}}""",
        )
        assertEquals(listOf(TranscriptRole.ASSISTANT to "The row says [Thinking removed] there."), said)
    }
}

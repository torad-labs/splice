// V4-354: the existing paged reader merges Claude Code's assistant records by message.id and must
// carry that id on the assembled message so a perf row can select the reply it actually caused.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path

class TranscriptMessageIdTest {
    @Test
    fun `the assembled reply keeps the id that joined its two lines`(@TempDir root: Path) {
        val file = root.resolve("projects/project/sess-id.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            listOf(
                """{"type":"user","message":{"role":"user","content":"the prompt"}}""",
                """{"type":"assistant","message":{"id":"msg_exact_42","content":[{"type":"text","text":"first"}]}}""",
                """{"type":"assistant","message":{"id":"msg_exact_42","content":[{"type":"text","text":"second"}]}}""",
            ).joinToString("\n", postfix = "\n"),
        )

        val page = (TranscriptReader().page("sess-id", listOf(root), null, 100) as TranscriptLookup.Found).page
        val reply = page.messages.single { it.role == TranscriptRole.ASSISTANT }
        assertEquals("first\n\nsecond", reply.text)
        assertEquals("msg_exact_42", reply.messageId)
    }
}

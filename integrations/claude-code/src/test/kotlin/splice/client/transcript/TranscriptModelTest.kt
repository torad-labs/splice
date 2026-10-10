// NEW: Oct 10, 2026 — a session is served with the model that wrote its newest assistant message, and each message
// with its own, so the page can name the model a session is on and where it changed.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.TranscriptLookup
import java.nio.file.Path

class TranscriptModelTest {
    private fun reply(id: String, model: String, text: String) =
        """{"type":"assistant","message":{"id":"$id","model":"$model","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun `the session model is the newest assistant message's, even behind later user turns`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(
            listOf(
                ACTIVITY_USER,
                reply("m1", "claude-opus-5", "old"),
                reply("m2", "gpt-6.1", "new"),
                """{"type":"user","message":{"content":"and then?"}}""",
                """{"type":"user","message":{"content":"still there?"}}""",
            ),
        )
        assertEquals("gpt-6.1", fixture.reader.model(ACTIVITY_ID, listOf(tmp)))
    }

    @Test
    fun `a placeholder is not a model and a session with no assistant message has none`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(listOf(ACTIVITY_USER, reply("m1", "claude-opus-5", "real"), reply("m2", "<synthetic>", "stub")))
        assertEquals("claude-opus-5", fixture.reader.model(ACTIVITY_ID, listOf(tmp)))
        fixture.write(listOf(ACTIVITY_USER))
        assertNull(fixture.reader.model(ACTIVITY_ID, listOf(tmp)))
    }

    @Test
    fun `each assistant message in a page carries its own model`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(listOf(ACTIVITY_USER, reply("m1", "claude-opus-5", "a"), reply("m2", "gpt-6.1", "b")))
        val page = fixture.reader.page(ACTIVITY_ID, listOf(tmp), null, 100) as TranscriptLookup.Found
        assertEquals(listOf(null, "claude-opus-5", "gpt-6.1"), page.page.messages.map { it.source.model })
    }
}

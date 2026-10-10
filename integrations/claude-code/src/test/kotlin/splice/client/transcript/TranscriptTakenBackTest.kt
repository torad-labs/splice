// NEW: Oct 10, 2026 — a message Claude Code took back into the prompt (a Stop before any reply) is served as taken back,
// so the page does not draw it as sent with nothing after it.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.KIND_TAKEN_BACK
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import java.nio.file.Files
import java.nio.file.Path

private const val TAKEN_BACK_SESSION = "3f2a9c1e-0000-4000-8000-0000000000cc"

class TranscriptTakenBackTest {

    @TempDir
    lateinit var home: Path

    private fun file(vararg lines: String): Path {
        val file = home.resolve("projects/-w-repo/$TAKEN_BACK_SESSION.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun forward(vararg lines: String): List<TranscriptMessage> {
        file(*lines)
        val found = TranscriptReader().page(TAKEN_BACK_SESSION, listOf(home), null, 100)
        assertTrue(found is TranscriptLookup.Found, found.toString())
        return (found as TranscriptLookup.Found).page.messages
    }

    private fun person(uuid: String, parent: String, text: String) =
        """{"type":"user","uuid":"$uuid","parentUuid":"$parent","message":{"role":"user","content":"$text"}}"""

    private fun reply(uuid: String, parent: String, text: String) =
        """{"type":"assistant","uuid":"$uuid","parentUuid":"$parent","message":{"id":"m-$uuid",""" +
            """"model":"claude-opus-5","content":[{"type":"text","text":"$text"}]}}"""

    private fun attachment(uuid: String, parent: String) =
        """{"type":"attachment","uuid":"$uuid","parentUuid":"$parent","attachment":{"type":"total_tokens_reminder"}}"""

    @Test
    fun `a message whose branch the next message abandoned is taken back, the next one is not`() {
        val messages = forward(
            reply("a0", "root", "ready"),
            person("u1", "a0", "take your time with the rounding"),
            attachment("t1", "u1"),
            person("u2", "a0", "Please run the tests again."),
            reply("a1", "u2", "running"),
        )
        assertEquals(
            listOf(null, KIND_TAKEN_BACK, null, null),
            messages.map { it.source.kind },
        )
        assertEquals(
            listOf("ready", "take your time with the rounding", "Please run the tests again.", "running"),
            messages.map { it.text },
        )
    }

    @Test
    fun `a message that was answered is not taken back even when a later message shares its parent`() {
        val messages = forward(
            reply("a0", "root", "ready"),
            person("u1", "a0", "first"),
            attachment("t1", "u1"),
            reply("a1", "t1", "answered through the attachment"),
            person("u2", "a0", "branched from the same place"),
        )
        assertEquals(listOf(null, null, null, null), messages.map { it.source.kind })
    }

    @Test
    fun `the newest message with nothing after it is never called, and a read from the end agrees`() {
        file(
            reply("a0", "root", "ready"),
            person("u1", "a0", "stopped, or still thinking"),
            person("u2", "a0", "sent again"),
            person("u3", "u2", "newest"),
        )
        val back = TranscriptReader().pageBefore(TAKEN_BACK_SESSION, listOf(home), null, 100)
        val messages = (back as TranscriptLookup.Found).page.messages
        assertEquals(listOf(null, KIND_TAKEN_BACK, null, null), messages.map { it.source.kind })
    }

    @Test
    fun `a card's last line keeps the reply before a message that was taken back`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(
            listOf(
                reply("a0", "root", "the previous reply"),
                person("u1", "a0", "take your time with it"),
                attachment("t1", "u1"),
                person("u2", "a0", "Please run the tests again."),
            ),
        )
        assertEquals("Please run the tests again.", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
        fixture.write(
            listOf(
                reply("a0", "root", "the previous reply"),
                person("u1", "a0", "take your time with it"),
                person("u2", "a0", "second try"),
                reply("a1", "u2", "answered"),
            ),
        )
        assertEquals("answered", fixture.reader.last(ACTIVITY_ID, listOf(tmp))?.text)
    }
}

// NEW: Oct 10, 2026 — a teammate's message is served as one, with its sender, and a stopped turn as a marker, so
// neither is drawn as the person's own words.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.KIND_INTERRUPTED
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION_ID = "3f2a9c1e-0000-4000-8000-0000000000bb"

class TranscriptReceivedTest {

    @TempDir
    lateinit var home: Path

    private fun read(vararg lines: String): List<TranscriptMessage> {
        val file = home.resolve("projects/-w-repo/$SESSION_ID.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        val found = TranscriptReader().page(SESSION_ID, listOf(home), null, 100)
        assertTrue(found is TranscriptLookup.Found, found.toString())
        return (found as TranscriptLookup.Found).page.messages
    }

    private val envelope = "Another Claude session sent a message:\\n" +
        "<cross-session-message from=\\\"uds:/run/x.sock\\\" from-name=\\\"splice-lead\\\">\\nPush is done.\\n" +
        "</cross-session-message>\\n\\nThis came from another Claude session."

    @Test
    fun `a teammate's message is a peer message with its sender, not a user turn, tagged or not`() {
        val messages = read(
            """{"type":"user","origin":{"kind":"peer","name":"splice-lead","body":"Push is done."},""" +
                """"isMeta":true,"message":{"role":"user","content":"$envelope"}}""",
            """{"type":"user","message":{"role":"user","content":"$envelope"}}""",
            """{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"continue"}}""",
        )
        assertEquals(listOf(TranscriptRole.PEER, TranscriptRole.PEER, TranscriptRole.USER), messages.map { it.role })
        assertEquals(listOf("splice-lead", "splice-lead", null), messages.map { it.source.from })
        assertEquals(listOf("Push is done.", "Push is done.", "continue"), messages.map { it.text })
    }

    @Test
    fun `a stopped turn or a refused tool call is an interrupted line, not the person speaking`() {
        val messages = read(
            """{"type":"user","message":{"role":"user","content":[""" +
                """{"type":"text","text":"[Request interrupted by user for tool use]"}]}}""",
            """{"type":"user","message":{"role":"user","content":"[Request interrupted by user]"}}""",
            """{"type":"user","message":{"role":"user","content":"do not interrupt me"}}""",
        )
        assertEquals(
            listOf(TranscriptRole.SYSTEM, TranscriptRole.SYSTEM, TranscriptRole.USER),
            messages.map { it.role },
        )
        assertEquals(listOf(KIND_INTERRUPTED, KIND_INTERRUPTED, null), messages.map { it.source.kind })
    }
}

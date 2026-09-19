// NEW: V4-131 — TranscriptReader.sentTexts reads a team chat's text from the SENDER's transcript by
// tool_use id: found ids carry their message, redacted like a page; every id it does not find is
// reported missing with the path it read, and a session no tree holds names every dir searched.
package console.v4131

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.sessions.SentTexts
import splice.core.sessions.TranscriptReader
import java.nio.file.Files
import java.nio.file.Path

private const val ID = "e5e5e5e5-0000-4000-8000-000000000005"

private const val CALL = """{"type":"assistant","message":{"id":"%s","role":"assistant",""" +
    """"content":[{"type":"tool_use","id":"%s","name":"%s","input":%s}]}}"""

private val LINES = listOf(
    """{"type":"user","message":{"role":"user","content":"go"}}""",
    CALL.format("m1", "toolu_a", "SendMessage", """{"to":"peer","message":"done, token=abcdefgh12345678"}"""),
    """{broken line mentioning toolu_b""",
    CALL.format("m2", "toolu_b", "SendMessage", """{"to":"lead","message":{"type":"shutdown_request"}}"""),
    CALL.format("m3", "toolu_c", "Read", """{"file_path":"/x"}"""),
)

class TranscriptSentTextsTest {

    @TempDir
    lateinit var home: Path

    @Test
    fun `wanted calls come back by id, and an id the file lacks is missing with the path read`() {
        val file = home.resolve(".claude/projects/-w/$ID.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, LINES.joinToString("\n", postfix = "\n"))
        val reader = TranscriptReader { listOf(home.resolve(".claude")) }
        assertEquals(
            SentTexts(
                path = file.toString(),
                texts = mapOf("toolu_a" to "done, token=[redacted]", "toolu_b" to """{"type":"shutdown_request"}"""),
                missing = setOf("toolu_c", "toolu_z"),
            ),
            reader.sentTexts(ID, null, setOf("toolu_a", "toolu_b", "toolu_c", "toolu_z")),
        )
    }

    @Test
    fun `a session no tree holds, or a malformed id, reports every id missing`() {
        val reader = TranscriptReader { listOf(home.resolve(".claude"), home.resolve(".claude-x")) }
        val searched = listOf(".claude", ".claude-x").map { home.resolve(it).resolve("projects").toString() }
        val none = SentTexts(null, emptyMap(), setOf("toolu_a"), searched)
        assertEquals(none, reader.sentTexts(ID, null, setOf("toolu_a")))
        assertEquals(none, reader.sentTexts("../etc", null, setOf("toolu_a")))
    }
}

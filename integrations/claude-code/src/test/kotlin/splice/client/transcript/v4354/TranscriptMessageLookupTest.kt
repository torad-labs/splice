// V4-354: a default-install request joins the client-facing message id to Claude Code's own
// redacted transcript. The fixture is the transcript format, not a pre-assembled message list: two
// records of one reply must merge, a later turn must not leak into this one, and no credential text
// may leave the lookup. A missing or pruned file answers in words rather than an empty conversation.
package splice.client.transcript.v4354

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.transcript.TranscriptMessageLookup
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION = "sess-v4354"
private const val RESPONSE = "msg_42_7"
private const val PLANTED_CREDENTIAL = "DEMO_VALUE_12345"

class TranscriptMessageLookupTest {
    /** One Claude Code project transcript: the selected reply spans two lines with the same id. */
    private fun transcript(root: Path) {
        val file = root.resolve("projects/project/$SESSION.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            listOf(
                """{"type":"user","message":{"role":"user","content":"earlier prompt"}}""",
                """{"type":"assistant","message":{"id":"msg_earlier","content":[{"type":"text","text":"earlier reply"}]}}""",
                """{"type":"user","message":{"role":"user","content":"why is the build red"}}""",
                """{"type":"assistant","message":{"id":"$RESPONSE","content":[{"type":"text","text":"first answer"}]}}""",
                """{"type":"assistant","message":{"id":"$RESPONSE","content":[{"type":"text","text":"second answer"}]}}""",
                """{"type":"user","message":{"role":"user","content":"Authorization: Bearer $PLANTED_CREDENTIAL"}}""",
            ).joinToString("\n", postfix = "\n"),
        )
    }

    @Test
    fun `the selected reply is merged by its id, with only the conversation up to it`(@TempDir root: Path) {
        transcript(root)

        val found = TranscriptMessageLookup().lookup(SESSION, listOf(root), RESPONSE) as MessageConversation.Found

        assertEquals(RESPONSE, found.responseId)
        assertEquals(
            listOf("earlier prompt", "earlier reply", "why is the build red", "first answer\n\nsecond answer"),
            found.messages.map { it.text },
        )
        val reply = found.messages.single { it.messageId == RESPONSE }
        assertEquals(TranscriptRole.ASSISTANT, reply.role)
        assertFalse(
            found.messages.any { it.text.contains(PLANTED_CREDENTIAL) },
            "a later message must not join this turn",
        )
    }

    @Test
    fun `no saved transcript or no matching reply is a named absence`(@TempDir root: Path) {
        val lookup = TranscriptMessageLookup()
        val absent = lookup.lookup(SESSION, listOf(root), RESPONSE) as MessageConversation.Missing
        assertTrue(absent.reason.contains("transcript", ignoreCase = true))

        transcript(root)
        val pruned = lookup.lookup(SESSION, listOf(root), "msg_not_kept") as MessageConversation.Missing
        assertTrue(pruned.reason.contains("reply", ignoreCase = true))
    }

    @Test
    fun `a credential in the selected reply is redacted before leaving the lookup`(@TempDir root: Path) {
        transcript(root)
        val file = root.resolve("projects/project/$SESSION.jsonl")
        Files.writeString(
            file,
            Files.readString(file).replace("second answer", "Authorization: Bearer $PLANTED_CREDENTIAL"),
        )

        val found = TranscriptMessageLookup().lookup(SESSION, listOf(root), RESPONSE) as MessageConversation.Found
        assertFalse(found.messages.any { it.text.contains(PLANTED_CREDENTIAL) })
        assertTrue(found.messages.any { it.text.contains("[redacted]") })
    }

    @Test
    fun `a preferred copy missing the reply does not hide the copy that has it`(@TempDir dir: Path) {
        val first = dir.resolve("first")
        val second = dir.resolve("second")
        val pages = object : SessionTranscripts {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
                val own = roots.first()
                val messages = if (own == second) {
                    listOf(TranscriptMessage(1, TranscriptRole.ASSISTANT, null, "the answer", messageId = RESPONSE))
                } else {
                    listOf(TranscriptMessage(0, TranscriptRole.USER, null, "old copy"))
                }
                return TranscriptLookup.Found(TranscriptPage(sessionId, own.toString(), messages, null, emptyMap()))
            }

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }

        val found = TranscriptMessageLookup(pages).lookup(SESSION, listOf(first, second), RESPONSE)
            as MessageConversation.Found
        assertEquals("the answer", found.messages.single { it.messageId == RESPONSE }.text)
    }

    @Test
    fun `older messages count past the bounded context instead of entering the response`(@TempDir dir: Path) {
        val messages = (0 until 120).map { index ->
            TranscriptMessage(index.toLong(), TranscriptRole.USER, null, "prompt $index")
        } + TranscriptMessage(120, TranscriptRole.ASSISTANT, null, "the answer", messageId = RESPONSE)
        val pages = object : SessionTranscripts {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup =
                TranscriptLookup.Found(TranscriptPage(sessionId, roots.first().toString(), messages, null, emptyMap()))

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }

        val found = TranscriptMessageLookup(pages).lookup(SESSION, listOf(dir), RESPONSE)
            as MessageConversation.Found
        assertEquals(20L, found.earlier)
        assertEquals(101, found.messages.size)
        assertEquals("prompt 20", found.messages.first().text)
    }

    @Test
    fun `a reply divided by the page byte cap is still one selected message`(@TempDir dir: Path) {
        val pages = object : SessionTranscripts {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
                val first = cursor == null
                val messages = if (first) {
                    listOf(
                        TranscriptMessage(0, TranscriptRole.USER, null, "the prompt"),
                        TranscriptMessage(1, TranscriptRole.ASSISTANT, null, "first", messageId = RESPONSE),
                    )
                } else {
                    listOf(
                        TranscriptMessage(2, TranscriptRole.ASSISTANT, null, "second", messageId = RESPONSE),
                        TranscriptMessage(3, TranscriptRole.USER, null, "later prompt"),
                    )
                }
                return TranscriptLookup.Found(
                    TranscriptPage(
                        sessionId,
                        roots.first().toString(),
                        messages,
                        if (first) "50.2" else null,
                        emptyMap(),
                    ),
                )
            }

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }

        val found = TranscriptMessageLookup(pages).lookup(SESSION, listOf(dir), RESPONSE)
            as MessageConversation.Found
        assertEquals("the prompt", found.messages.first().text)
        assertEquals("first\n\nsecond", found.messages.single { it.messageId == RESPONSE }.text)
        assertFalse(found.messages.any { it.text == "later prompt" })
    }
}

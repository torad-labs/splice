// V4-354: a default-install request joins the client-facing message id to Claude Code's own
// redacted transcript. The fixture is the transcript format, not a pre-assembled message list: two
// records of one reply must merge, a later turn must not leak into this one, and no credential text
// may leave the lookup. A missing or pruned file answers in words rather than an empty conversation.
package splice.client.transcript.v4354

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.transcript.TranscriptLocator
import splice.client.transcript.TranscriptMessageLookup
import splice.client.transcript.TranscriptReader
import splice.core.util.ElapsedClock
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
    fun `a long conversation read ends with a reason instead of scanning every page`(@TempDir root: Path) {
        var elapsed = 0L
        var reads = 0
        val copies = listOf(root.resolve("first"), root.resolve("second"))
        val pages = object : SessionTranscripts {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
                reads++
                elapsed += 6_000L
                val preferred = roots.first() == copies.first()
                val message = if (preferred) {
                    TranscriptMessage(reads.toLong(), TranscriptRole.USER, null, "synthetic history")
                } else {
                    TranscriptMessage(
                        reads.toLong(),
                        TranscriptRole.ASSISTANT,
                        null,
                        "partial reply",
                        messageId = RESPONSE,
                    )
                }
                val next = if (preferred || reads == 100) null else "$reads.0"
                return TranscriptLookup.Found(
                    TranscriptPage(
                        sessionId,
                        roots.first().toString(),
                        listOf(message),
                        next,
                        emptyMap(),
                    ),
                )
            }

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }
        val answer = TranscriptMessageLookup(pages, ElapsedClock { elapsed }).lookup(SESSION, copies, RESPONSE)
        assertTrue(answer is MessageConversation.Unavailable, "a slow read must say why it stopped, not claim absence")
        assertTrue((answer as MessageConversation.Unavailable).reason.contains("Open the session"))
        assertEquals(2, reads, "the next page is not read after the whole lookup's time budget")
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

class TranscriptLookupScaleTest {
    private fun history(root: Path): Path {
        val file = root.resolve("projects/synthetic/$SESSION.jsonl")
        Files.createDirectories(file.parent)
        val payload = "synthetic history ".repeat(42)
        Files.newBufferedWriter(file).use { out ->
            repeat(120_000) { index ->
                val message = if (index % 2 == 0) {
                    """{"type":"user","message":{"content":"$payload"}}"""
                } else {
                    """{"type":"assistant","message":{"id":"msg_synthetic_$index","content":[{"type":"text","text":"$payload"}]}}"""
                }
                out.appendLine(message)
            }
            out.appendLine("""{"type":"user","message":{"content":"selected prompt"}}""")
            out.appendLine(
                """{"type":"assistant","message":{"id":"$RESPONSE","content":[{"type":"text","text":"first"}]}}""",
            )
            out.appendLine(
                """{"type":"assistant","message":{"id":"$RESPONSE","content":[{"type":"text","text":"second"}]}}""",
            )
            out.appendLine("""{"type":"user","message":{"content":"later prompt"}}""")
        }
        return file
    }

    @Test
    fun `a recent selected reply decodes its context rather than every history page`(@TempDir root: Path) {
        val file = history(root)
        val locating = System.nanoTime()
        assertEquals(file, TranscriptLocator().locate(listOf(root), SESSION))
        val locationMs = (System.nanoTime() - locating) / 1_000_000
        val decoding = System.nanoTime()
        var records = 0
        Files.newBufferedReader(file).use { input ->
            input.forEachLine { if (Json.parseToJsonElement(it) is JsonObject) records++ }
        }
        val decodeMs = (System.nanoTime() - decoding) / 1_000_000
        val opened = CountingTranscriptOpener()
        val reader = TranscriptReader(opened)
        var pagesRead = 0
        val pages = object : SessionTranscripts by reader {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
                pagesRead++
                return reader.page(sessionId, roots, cursor, limit)
            }
        }
        val lookup = TranscriptMessageLookup(pages)
        val started = System.nanoTime()
        val answer = lookup.lookup(SESSION, listOf(root), RESPONSE)
        val lookupMs = (System.nanoTime() - started) / 1_000_000
        println(
            "conversation-profile bytes=${Files.size(file)} records=$records " +
                "location_ms=$locationMs decode_ms=$decodeMs lookup_ms=$lookupMs " +
                "pages=$pagesRead state=${answer::class.simpleName}",
        )
        assertTrue(answer is MessageConversation.Found, "the request must find a reply in realistic synthetic history")
        val found = answer as MessageConversation.Found
        assertEquals("first\n\nsecond", found.messages.last().text)
        assertFalse(found.messages.any { it.text == "later prompt" })
        assertTrue(pagesRead <= 2, "one request must not decode every history page: $pagesRead pages")
        assertEquals(119_901L, found.earlier, "the earlier count remains exact without decoding historical payloads")
    }

    @Test
    fun `unchanged and appended replies read a request window not the history`(@TempDir root: Path) {
        val file = history(root)
        val opened = CountingTranscriptOpener()
        val lookup = TranscriptMessageLookup(TranscriptReader(opened))
        val found = lookup.lookup(SESSION, listOf(root), RESPONSE) as MessageConversation.Found
        opened.bytes = 0L
        val warmStarted = System.nanoTime()
        val warm = lookup.lookup(SESSION, listOf(root), RESPONSE) as MessageConversation.Found
        val warmMs = (System.nanoTime() - warmStarted) / 1_000_000
        assertEquals(found, warm)
        assertTrue(opened.bytes < 1 shl 20, "an unchanged request reread ${opened.bytes} history bytes")
        println("conversation-warm lookup_ms=$warmMs read_bytes=${opened.bytes}")
        Files.writeString(
            file,
            """{"type":"assistant","message":{"id":"msg_new","content":[{"type":"text","text":"new reply"}]}}""" + "\n",
            StandardOpenOption.APPEND,
        )
        opened.bytes = 0L
        val appended = lookup.lookup(SESSION, listOf(root), "msg_new") as MessageConversation.Found
        assertEquals("new reply", appended.messages.last().text)
        assertEquals(119_903L, appended.earlier)
        assertTrue(opened.bytes < 1 shl 20, "one appended response reread ${opened.bytes} history bytes")
        println("conversation-append read_bytes=${opened.bytes}")
    }
}

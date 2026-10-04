package splice.sessions.http.v4344

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.http.SessionHistoryRoute
import splice.sessions.http.SessionHistoryRowOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path

private const val MARKER = "PRIVATE_CONVERSATION_MARKER"

class SessionTranscriptOffTest {
    @TempDir lateinit var home: Path

    private fun namedSession(): SessionRecord = SessionRecord(
        pid = 71L,
        sessionId = "session-id",
        cwd = "/work/atlas",
        name = MARKER,
        kind = "interactive",
        version = null,
        status = SessionStatus("busy"),
        startedAt = null,
        updatedAt = 1L,
        messagingSocketPath = null,
        route = SessionRoute.Head("claudex"),
        availability = SessionAvailability.GONE,
    )

    @Test
    fun `conversation route checks the hot switch before registry and transcript reads`() {
        var enabled = false
        var registryReads = 0
        var transcriptReads = 0
        var sentTextReads = 0
        val named = namedSession()
        val registry = object : SessionSource {
            override fun read(): List<SessionRecord> {
                registryReads++
                return emptyList()
            }
            override fun list(): SessionListing = SessionListing(listOf(named))
        }
        val source = object : SessionTranscripts {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
                transcriptReads++
                return TranscriptLookup.Found(
                    TranscriptPage(
                        sessionId,
                        "$home/$MARKER.jsonl",
                        listOf(TranscriptMessage(0, TranscriptRole.USER, 1, MARKER)),
                        null,
                        emptyMap(),
                    ),
                )
            }
            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts {
                sentTextReads++
                return SentTexts("$home/$MARKER.jsonl", mapOf("tool" to MARKER), emptySet())
            }
        }
        val routes = SessionsRoutes(
            registry,
            source,
            vanilla = home,
            viewEnabled = SessionTranscriptViewEnabled { enabled },
        )
        val reply = routes.transcript("session-id", null, 50)
        assertEquals(HttpStatusCode.OK, reply.status)
        val off = Json.parseToJsonElement(reply.body).jsonObject
        assertEquals("off", off.getValue("state").jsonPrimitive.content)
        assertEquals(
            "Transcript view is off. Turn it on in Request detail.",
            off.getValue("reason").jsonPrimitive.content,
        )
        assertEquals(setOf("state", "reason"), off.keys)
        assertFalse(reply.body.contains(MARKER))
        assertEquals(0, registryReads)
        assertEquals(0, transcriptReads)
        val sent = routes.sentTexts("session-id", "claudex", setOf("tool"))
        assertEquals(setOf("tool"), sent.missing)
        assertFalse(sent.texts.values.any { it.contains(MARKER) })
        assertEquals(0, sentTextReads, "team hand-offs cannot read transcript text while off")
        assertFalse(routes.sessionsJson().contains(MARKER), "the registry title is conversation text too")
        enabled = true
        assertTrue(routes.transcript("session-id", null, 50).body.contains(MARKER))
        assertEquals(1, transcriptReads, "a hot switch enables the next request without remounting")
    }

    @Test
    fun `history route checks the hot switch before any source or registry read`() {
        var enabled = false
        var historyReads = 0
        var registryReads = 0
        val registry = object : SessionSource {
            override fun read(): List<SessionRecord> = emptyList()
            override fun list(): SessionListing {
                registryReads++
                return SessionListing(emptyList())
            }
        }
        val source = SessionHistorySource {
            historyReads++
            SessionHistoryScan(listOf(SessionHistoryEntry("session-id", MARKER, "/work/$MARKER", null, 1L, true, true)))
        }
        val routes = SessionHistoryRoute(
            registry,
            source,
            listOf(SessionHistoryRoot(null, home)),
            SessionHistoryRowOf { buildJsonObject { put("name", MARKER) } },
            SessionTranscriptViewEnabled { enabled },
        )
        val reply = routes.page(MARKER, null, 50)
        assertEquals(HttpStatusCode.OK, reply.status)
        val off = Json.parseToJsonElement(reply.body).jsonObject
        assertEquals("off", off.getValue("state").jsonPrimitive.content)
        assertEquals(
            "Transcript view is off. Turn it on in Request detail.",
            off.getValue("reason").jsonPrimitive.content,
        )
        assertEquals(setOf("state", "reason"), off.keys)
        assertFalse(reply.body.contains(MARKER))
        assertEquals(0, historyReads)
        assertEquals(0, registryReads)
        enabled = true
        assertTrue(routes.page(MARKER, null, 50).body.contains(MARKER))
        assertEquals(1, historyReads, "the next request reads again after a hot enable")
    }
}

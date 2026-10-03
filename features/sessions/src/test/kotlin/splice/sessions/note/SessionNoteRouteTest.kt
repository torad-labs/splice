// POST /api/sessions/{id}/message: what is refused before anything is sent, and that an accepted note is "submitted",
// never "delivered".
package splice.sessions.note

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource

class SessionNoteRouteTest {
    private val hi = """{"text":"hi"}"""

    private fun record(
        id: String = "s-1",
        socket: String? = "/run/user/1000/cc-socks/1.sock",
        availability: SessionAvailability = SessionAvailability.LIVE,
        version: String = "2.1.285",
    ) = SessionRecord(
        pid = 1,
        sessionId = id,
        cwd = null,
        name = null,
        kind = null,
        version = version,
        status = "idle",
        statusUpdatedAt = null,
        startedAt = null,
        updatedAt = 1,
        messagingSocketPath = socket,
        route = SessionRoute.Unknown,
        availability = availability,
    )

    private class Recording(private val answer: NoteOutcome = NoteOutcome.Submitted("m-1")) : SessionNoteSender {
        val sent = mutableListOf<Pair<SessionRecord, String>>()

        override suspend fun send(target: SessionRecord, text: String): NoteOutcome {
            sent += target to text
            return answer
        }
    }

    private fun route(sender: SessionNoteSender?, vararg records: SessionRecord) = SessionNoteRoute(
        object : SessionSource {
            override fun read() = records.toList()

            override fun list() = SessionListing(records.toList())
        },
        sender,
    )

    private fun post(route: SessionNoteRoute, id: String, body: String): JsonReply =
        runBlocking { route.post(id, body) }

    private fun field(reply: JsonReply, name: String) =
        Json.parseToJsonElement(reply.body).jsonObject[name]!!.jsonPrimitive.content

    @Test
    fun `a note to a live session is submitted, and the answer says delivery is unknown`() {
        val sender = Recording()
        val reply = post(route(sender, record()), "s-1", """{"text":"run the gate"}""")
        assertEquals(HttpStatusCode.Accepted, reply.status)
        assertEquals("true", field(reply, "submitted"))
        assertEquals("m-1", field(reply, "message_id"))
        assertEquals("unknown", field(reply, "delivery"))
        assertEquals("run the gate", sender.sent.single().second)
    }

    @Test
    fun `a note to a session on 2-1-286 is handed to the sender with that version intact`() {
        val sender = Recording()
        val reply = post(route(sender, record(version = "2.1.286")), "s-1", hi)
        assertEquals(HttpStatusCode.Accepted, reply.status)
        assertEquals("2.1.286", sender.sent.single().first.version)
    }

    @Test
    fun `a body that is not the note is refused before anything is sent`() {
        val sender = Recording()
        val route = route(sender, record())
        for (body in listOf("", "not json", "[]", """{"text":7}""", """{}""", """{"text":"   "}""")) {
            assertEquals(HttpStatusCode.BadRequest, post(route, "s-1", body).status, body)
        }
        val long = "x".repeat(MAX_NOTE_CHARS + 1)
        assertEquals(HttpStatusCode.BadRequest, post(route, "s-1", """{"text":"$long"}""").status)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `the target is the registry's, so any other field in the request is ignored`() {
        val sender = Recording()
        val body = """{"text":"hi","socket":"/tmp/evil.sock","to":"uds:/tmp/evil.sock"}"""
        post(route(sender, record()), "s-1", body)
        assertEquals("/run/user/1000/cc-socks/1.sock", sender.sent.single().first.messagingSocketPath)
    }

    @Test
    fun `no such session, one that is gone and one with no socket are each refused by name`() {
        val sender = Recording()
        assertEquals(HttpStatusCode.NotFound, post(route(sender, record()), "other", hi).status)
        val gone = post(route(sender, record(availability = SessionAvailability.GONE)), "s-1", hi)
        assertEquals("the session is not running", field(gone, "error"))
        val bare = post(route(sender, record(socket = null)), "s-1", hi)
        assertEquals("the session has no messaging socket", field(bare, "error"))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a stale session is running, only not heard from lately, so it takes the note`() {
        // why: STALE is a pid still alive whose registration has not refreshed (SessionRegistry.kt). Refusing it, the
        // session page said claude-builder was not running while Sessions listed it Working and Requests streaming.
        val sender = Recording()
        val stale = post(route(sender, record(availability = SessionAvailability.STALE)), "s-1", hi)
        assertEquals(HttpStatusCode.Accepted, stale.status)
        assertEquals(listOf("s-1"), sender.sent.map { it.first.sessionId })
    }

    @Test
    fun `two running sessions on one socket, or one id twice, are ambiguous and get nothing`() {
        val sender = Recording()
        val shared = post(route(sender, record("s-1"), record("s-2")), "s-1", hi)
        assertEquals(HttpStatusCode.Conflict, shared.status)
        val staleTwin = record("s-2", availability = SessionAvailability.STALE)
        assertEquals(HttpStatusCode.Conflict, post(route(sender, record("s-1"), staleTwin), "s-1", hi).status)
        val other = record("s-1", socket = "/run/user/1000/cc-socks/2.sock")
        val twice = post(route(sender, record("s-1"), other), "s-1", hi)
        assertEquals("more than one running session has that id", field(twice, "error"))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `what the sender refuses or fails at is what the caller is told`() {
        val refused = Recording(NoteOutcome.Refused(HttpStatusCode.UnprocessableEntity, "version"))
        assertEquals(HttpStatusCode.UnprocessableEntity, post(route(refused, record()), "s-1", hi).status)
        val failed = post(route(Recording(NoteOutcome.Failed("connection refused")), record()), "s-1", hi)
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertEquals("connection refused", field(failed, "error"))
    }

    @Test
    fun `a daemon with no sender says it cannot send`() {
        assertEquals(HttpStatusCode.NotImplemented, post(route(null, record()), "s-1", hi).status)
    }
}

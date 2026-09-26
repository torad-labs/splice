// NEW: V4-239 — GET /api/heads/{head}/trace: what `splice trace <head>` prints, as JSON for the console.
// The files are written by the SAME TraceStore the daemon uses (TraceCommandTest's fixture), so the
// route is tested against the writer's real shape. The list carries no body; one turn's read carries
// its records as written, headers redacted as they were stored.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.wire.BAD_LAST
import splice.head.wire.ClientInbound
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.http.JsonReply
import splice.upstream.sse.WireAttempt
import java.nio.file.Files
import java.nio.file.Path

private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z
private const val HEAD = "openrouter"

class TraceRouteTest {

    private val noCompaction = object : HeadCompactSource {
        override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
    }

    private val heads = TurnsHeadLookup { name ->
        if (name == HEAD) listOf(TurnsHead(HEAD, noCompaction)) else emptyList()
    }

    private fun meta(session: String) = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-openrouter--m1",
        upstreamModel = "m1",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = session,
    )

    private fun attempt(n: Int) = WireAttempt(
        attempt = 1,
        url = "https://openrouter.ai/api/v1/chat/completions",
        requestHeaders = mapOf("Authorization" to "[redacted]", "content-type" to "application/json"),
        requestBody = """{"upstream":$n}""",
        requestEncoding = null,
        status = 200,
        responseHeaders = mapOf("x-request-id" to "r$n"),
        errorText = null,
        failure = null,
        durationMs = 40,
    )

    /** Turns as the daemon writes them: turn-1 (session alpha) and turn-2 (session beta) ended, and
     *  turn-3 (session gamma) with one attempt and no ending when [open] is true. */
    private fun writeTrace(dir: Path, open: Boolean = false) {
        val ids = ArrayDeque(listOf("turn-1", "turn-2", "turn-3"))
        val store = TraceStore(
            ActivityDays(dir, HEAD, 7, WallClock { DAY_ONE }, true),
            HEAD,
            maxBodyChars = 1 shl 20,
            now = WallClock { DAY_ONE },
            ids = TurnIdMint { ids.removeFirst() },
        )
        listOf("alpha-session", "beta-session").forEachIndexed { n, session ->
            val trace = store.begin(
                meta(session),
                ClientInbound("POST", "/v1/messages", mapOf("authorization" to "[redacted]"), """{"client":$n}"""),
            )
            trace.responseText("data: {\"n\":$n}\n\n")
            trace.attempted(attempt(n))
            trace.clientFrame("event: message_start\ndata: {}\n\n")
            trace.finish("ok", PerfSnapshot(mapOf("total" to 120L + n), mapOf("in_tokens" to 3L)))
        }
        if (open) {
            store.begin(meta("gamma-session"), ClientInbound("POST", "/v1/messages", emptyMap(), "{}"))
                .attempted(attempt(2))
        }
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
    }

    private fun route(dir: Path?) = TraceRoute(heads, { dir }, Dispatchers.Unconfined)

    private fun read(
        dir: Path?,
        last: String? = null,
        session: String? = null,
        turn: String? = null,
        head: String = HEAD,
    ) = runBlocking { route(dir).read(head, TraceQuery(last, session, turn)) }

    private fun JsonReply.json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun JsonObject.turns() = getValue("turns").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    @Test
    fun `the list is the verb's table as fields, oldest first, with no body in it`(@TempDir dir: Path) {
        writeTrace(dir)

        val reply = read(dir)

        assertEquals(HttpStatusCode.OK, reply.status)
        val payload = reply.json()
        assertEquals(HEAD, payload.str("head"))
        assertEquals("$dir/$HEAD-YYYY-MM-DD.jsonl", payload.str("files"))
        assertEquals("2", payload.str("on_disk"))
        assertEquals("0", payload.str("skipped_lines"))
        val turns = payload.turns()
        assertEquals(listOf("turn-1", "turn-2"), turns.map { it.str("id") })
        val first = turns.first()
        assertEquals("alpha-session", first.str("session"))
        assertEquals("m1", first.str("model"))
        assertEquals(DAY_ONE.toString(), first.str("ts"))
        assertFalse(first.getValue("open").jsonPrimitive.boolean)
        assertEquals("ok", first.str("outcome"))
        assertEquals("1", first.str("rounds"))
        assertEquals("1", first.str("attempts"))
        assertEquals("120", first.str("total_ms"))
        assertEquals("121", turns[1].str("total_ms"))
        listOf("{\\\"upstream\\\"", "{\\\"client\\\"", "message_start", "data: ").forEach { body ->
            assertFalse(reply.body.contains(body), "a body reached the list: $body in ${reply.body}")
        }
    }

    @Test
    fun `last keeps the newest turns and session narrows by prefix, as the verb's flags do`(@TempDir dir: Path) {
        writeTrace(dir)

        assertEquals(listOf("turn-2"), read(dir, last = "1").json().turns().map { it.str("id") })
        assertEquals(listOf("turn-2"), read(dir, session = "beta").json().turns().map { it.str("id") })
        val blank = read(dir, last = " ", session = "").json().turns()
        assertEquals(listOf("turn-1", "turn-2"), blank.map { it.str("id") }, "blank reads as absent")
    }

    @Test
    fun `an open turn reads as open, counted from its attempts`(@TempDir dir: Path) {
        writeTrace(dir, open = true)

        val open = read(dir).json().turns().last()

        assertEquals("turn-3", open.str("id"))
        assertTrue(open.getValue("open").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(null as String?), open.getValue("outcome"))
        assertEquals("1", open.str("rounds"))
        assertEquals("1", open.str("attempts"))
    }

    @Test
    fun `one turn carries its records as written - bodies whole, headers as redacted`(@TempDir dir: Path) {
        writeTrace(dir)

        val reply = read(dir, turn = "turn-2")

        assertEquals(HttpStatusCode.OK, reply.status)
        val payload = reply.json()
        assertEquals("turn-2", payload.getValue("turn").jsonObject.str("id"))
        val records = payload.getValue("records").jsonArray.map { it.jsonObject }
        assertEquals(listOf("attempt", "turn"), records.map { it.str("kind") })
        val request = records.first().getValue("request").jsonObject
        assertEquals("""{"upstream":1}""", request.str("body"))
        assertEquals("[redacted]", request.getValue("headers").jsonObject.str("Authorization"))
        assertEquals("""{"client":1}""", records.last().getValue("client").jsonObject.str("body"))
        assertFalse(reply.body.contains("{\\\"upstream\\\":0}"), "the other turn is not served")
    }

    @Test
    fun `a missing turn, an unknown head, a bad count and an unwired dir each answer in words`(@TempDir dir: Path) {
        writeTrace(dir)

        val missing = read(dir, turn = "turn-9")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertTrue(missing.body.contains("no turn turn-9 in $HEAD's trace"), missing.body)
        val unknown = read(dir, head = "nope")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.body.contains("unknown head: nope"), unknown.body)
        listOf("0", "-1", "x").forEach { last ->
            val refused = read(dir, last = last)
            assertEquals(HttpStatusCode.BadRequest, refused.status, last)
            assertTrue(refused.body.contains(BAD_LAST), refused.body)
        }
        val unwired = read(null)
        assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
        assertTrue(unwired.body.contains(TRACE_UNWIRED), unwired.body)
    }

    @Test
    fun `a trace dir that cannot be read fails in words, never an empty list - V4-286`(@TempDir tmp: Path) {
        val notADir = Files.writeString(tmp.resolve("trace"), "a file where the directory should be")

        val reply = read(notADir)

        assertEquals(HttpStatusCode.InternalServerError, reply.status)
        assertTrue(reply.body.contains("cannot read $HEAD's trace under $notADir"), reply.body)
    }

    @Test
    fun `a head with no files on disk is an empty list, not a failure`(@TempDir dir: Path) {
        val reply = read(dir)

        assertEquals(HttpStatusCode.OK, reply.status)
        assertTrue(reply.json().turns().isEmpty())
        assertEquals("0", reply.json().str("on_disk"))
    }
}

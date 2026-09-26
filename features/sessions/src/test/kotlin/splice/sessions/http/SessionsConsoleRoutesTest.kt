// NEW: V4-130 — the console's session reads: repo, team and the edges summary on every /api/sessions
// row, the per-session and board-wide edges routes with direction derived per asked session, and the
// transcript route resolving a session's head to its own tree through the registry. Every payload is
// asserted as the whole JSON object the console receives, so a key that drifts fails by name.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.query.SessionHead
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Files
import java.nio.file.Path

private const val NOW = 1_789_725_600_000L
private const val ALPHA = "a1a1a1a1-0000-4000-8000-000000000001"
private const val BETA = "b2b2b2b2-0000-4000-8000-000000000002"
private const val GAMMA = "c3c3c3c3-0000-4000-8000-000000000003"

class SessionsConsoleRoutesTest {

    @TempDir
    lateinit var tmp: Path

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** alpha (codex head, named, in a repo), beta (plain claude, addressed by socket), gamma (no cwd). */
    private fun registry(): SessionRegistry {
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        val repo = Files.createDirectories(tmp.toRealPath().resolve("work/repo/src"))
        Files.createDirectories(repo.parent.resolve(".git"))
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"$ALPHA","name":"lead","cwd":"$repo","updatedAt":$NOW,""" +
                """"messagingSocketPath":"/run/a.sock"}""",
        )
        Files.writeString(
            dir.resolve("12.json"),
            """{"pid":12,"sessionId":"$BETA","cwd":"/usr","updatedAt":$NOW,"messagingSocketPath":"/run/b.sock"}""",
        )
        Files.writeString(dir.resolve("13.json"), """{"pid":13,"sessionId":"$GAMMA","updatedAt":$NOW}""")
        return SessionRegistry(
            sessionsDir = dir,
            routeOf = { pid -> if (pid == 11L) SessionRoute.Head("codex") else SessionRoute.Unknown },
            pidAlive = { true },
            clock = { NOW },
        )
    }

    /** alpha -> beta by address, beta -> "lead" by name (alpha held it then), gamma -> a name nobody held. */
    private fun stores(): ActivityStores {
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        stores.edges.record(MessageEdge(ALPHA, "uds:/run/b.sock", NOW - 30, "toolu_1"))
        stores.edges.record(MessageEdge(BETA, "lead", NOW - 20, "toolu_2", ALPHA))
        stores.edges.record(MessageEdge(GAMMA, "nobody", NOW - 10, "toolu_3"))
        // The single-threaded file lane: draining it puts every queued row on disk.
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        return stores
    }

    @Test
    fun `every row carries repo, team and its edges summary`() {
        val stores = stores()
        val routes = SessionsRoutes(
            registry(),
            TestTranscripts(),
            activity = ActivitySource { stores },
            vanilla = tmp.resolve(".claude"),
        )
        val rows = rowsOf(routes)
        val repo = tmp.toRealPath().resolve("work/repo").toString()
        assertEquals(json("""{"root":"$repo"}"""), rows.getValue(ALPHA)["repo"])
        assertEquals(
            "/usr",
            rows.getValue(BETA)["repo"]!!.jsonObject["root"]!!.jsonPrimitive.content,
            "an untrusted cwd reports itself, with its reason",
        )
        assertFalse(rows.getValue(GAMMA).containsKey("repo"), "no cwd, no repo key")
        rows.values.forEach { assertEquals("null", it["team"].toString(), "team is null until V4-131") }
        assertEquals(json("""{"sent":1,"received":1,"last_at":${NOW - 20}}"""), rows.getValue(ALPHA)["edges"])
        assertEquals(json("""{"sent":1,"received":1,"last_at":${NOW - 20}}"""), rows.getValue(BETA)["edges"])
        assertEquals(json("""{"sent":1,"received":0,"last_at":${NOW - 10}}"""), rows.getValue(GAMMA)["edges"])
    }

    @Test
    fun `edges report a stored name at its session's address and take their direction from the asked session`() {
        val stores = stores()
        val routes = SessionsRoutes(
            registry(),
            TestTranscripts(),
            activity = ActivitySource { stores },
            vanilla = tmp.resolve(".claude"),
        )
        val alpha = routes.edgeRoutes.edges(ALPHA)
        assertEquals(HttpStatusCode.OK, alpha.status)
        assertEquals(
            json(
                """{"session_id":"$ALPHA","edges":[""" +
                    """{"from":"$ALPHA","to":"uds:/run/b.sock","at":${NOW - 30},"direction":"out"},""" +
                    """{"from":"$BETA","to":"uds:/run/a.sock","at":${NOW - 20},"direction":"in"}]}""",
            ),
            json(alpha.body),
        )
        val board = json(routes.edgeRoutes.boardEdges().body)["sessions"]!!.jsonObject
        assertEquals(setOf(ALPHA, BETA, GAMMA), board.keys, "every registry session, empty arrays included")
        assertEquals(
            listOf("out nobody"),
            board.getValue(GAMMA).jsonArray.map {
                "${it.jsonObject["direction"]!!.jsonPrimitive.content} ${it.jsonObject["to"]!!.jsonPrimitive.content}"
            },
            "a name no session held when it was stored is reported verbatim and received by nobody",
        )
    }

    @Test
    fun `a name is received by the session that held it when stored, never by whoever holds it now`() {
        // Alpha holds "lead" now. Beta held it when gamma's first call was stored; the second is a row
        // from before names were stored with their session, and is nobody's.
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        stores.edges.record(MessageEdge(GAMMA, "lead", NOW - 5, "toolu_4", BETA))
        stores.edges.record(MessageEdge(GAMMA, "lead", NOW - 4, "toolu_5"))
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        val routes = SessionsRoutes(
            registry(),
            TestTranscripts(),
            activity = ActivitySource { stores },
            vanilla = tmp.resolve(".claude"),
        )
        val rows = rowsOf(routes)
        assertEquals(json("""{"sent":0,"received":1,"last_at":${NOW - 5}}"""), rows.getValue(BETA)["edges"])
        assertEquals(json("""{"sent":0,"received":0,"last_at":null}"""), rows.getValue(ALPHA)["edges"])
        assertEquals(
            json(
                """{"session_id":"$BETA","edges":[""" +
                    """{"from":"$GAMMA","to":"uds:/run/b.sock","at":${NOW - 5},"direction":"in"}]}""",
            ),
            json(routes.edgeRoutes.edges(BETA).body),
        )
    }

    private fun rowsOf(routes: SessionsRoutes): Map<String, JsonObject> =
        json(routes.sessionsJson())["sessions"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["session_id"]!!.jsonPrimitive.content }

    @Test
    fun `unwired stores answer the edges routes with a named 503 and leave the row summary off`() {
        val routes = SessionsRoutes(registry(), TestTranscripts(), vanilla = tmp.resolve(".claude"))
        assertEquals(HttpStatusCode.ServiceUnavailable, routes.edgeRoutes.edges(ALPHA).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, routes.edgeRoutes.boardEdges().status)
        val rows = json(routes.sessionsJson())["sessions"]!!.jsonArray.map { it.jsonObject }
        rows.forEach { assertFalse(it.containsKey("edges"), "zero sends nobody watched is not a count") }
    }

    @Test
    fun `the transcript route reads the session's own head tree and reports the path and the counts`() {
        // The reader is a fake here: the route's job is root priority and the payload shape. The real
        // reader over real bytes is proven where the two compose (daemon/control's
        // SessionsTranscriptCompositionTest).
        val own = tmp.resolve(".claude-codex")
        val vanilla = tmp.resolve(".claude")
        val file = own.resolve("projects/-w/$ALPHA.jsonl")
        val transcripts = TestTranscripts(
            pages = { session, roots, cursor, _ ->
                when {
                    cursor != null -> TranscriptLookup.Refused("not a cursor this daemon minted")
                    session == ALPHA -> {
                        assertEquals(listOf(own, vanilla), roots)
                        TranscriptLookup.Found(
                            TranscriptPage(
                                sessionId = ALPHA,
                                path = file.toString(),
                                messages = listOf(TranscriptMessage(0, TranscriptRole.USER, null, "the head's copy")),
                                next = null,
                                skipped = mapOf("unparseable" to 1, "sidechain" to 1, "attachment" to 1),
                            ),
                        )
                    }
                    else -> TranscriptLookup.Missing(roots.map { it.resolve("projects").toString() })
                }
            },
        )
        val routes = SessionsRoutes(
            registry(),
            transcripts,
            heads = mapOf("codex" to head(own)),
            vanilla = vanilla,
        )
        val reply = routes.transcript(ALPHA, null, null)
        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(
            json(
                """{"session_id":"$ALPHA","path":"$file","messages":[""" +
                    """{"index":0,"role":"user","text":"the head's copy"}],"next":null,""" +
                    """"unparseable_lines":1,"sidechain_records":1,"skipped_records":{"attachment":1}}""",
            ),
            json(reply.body),
        )
        val missing = routes.transcript(BETA, null, null)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(
            listOf(vanilla.resolve("projects").toString(), own.resolve("projects").toString()),
            json(missing.body)["searched"]!!.jsonArray.map { it.jsonPrimitive.content },
            "beta has no head: vanilla first, then every head's own tree",
        )
        assertEquals(HttpStatusCode.BadRequest, routes.transcript(ALPHA, "x", null).status)
    }

    private fun head(own: Path): SessionHead = SessionHead(transcriptRoot = own)
}

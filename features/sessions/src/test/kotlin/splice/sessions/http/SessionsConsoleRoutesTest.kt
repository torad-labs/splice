// NEW: V4-130 — the console's session reads: repo and team on every /api/sessions row, and the
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
import splice.core.config.UserHome
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.query.SessionHead
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.transcript.ModelMove
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionTail
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import splice.sessions.transcript.TranscriptToolUse
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
        // why: the resolver probes only trusted roots. The OS temp folder is /tmp on Linux, which it trusts, and
        // /var/folders on a Mac, which it does not, so the repo sits in the home on both.
        val rows = UserHome.within(tmp.toRealPath()) {
            rowsOf(
                SessionsRoutes(
                    registry(),
                    TestTranscripts(),
                    roots = TranscriptRoots(vanilla = tmp.resolve(".claude")),
                    activity = ActivitySource { stores },
                ),
            )
        }
        val repo = tmp.toRealPath().resolve("work/repo").toString()
        assertEquals(json("""{"root":"$repo"}"""), rows.getValue(ALPHA)["repo"])
        assertEquals(
            "/usr",
            rows.getValue(BETA)["repo"]!!.jsonObject["root"]!!.jsonPrimitive.content,
            "an untrusted cwd reports itself, with its reason",
        )
        assertFalse(rows.getValue(GAMMA).containsKey("repo"), "no cwd, no repo key")
        rows.values.forEach { assertEquals("null", it["team_name"].toString(), "no team, no team name") }
    }

    private fun rowsOf(routes: SessionsRoutes): Map<String, JsonObject> =
        json(routes.sessionsJson())["sessions"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["session_id"]!!.jsonPrimitive.content }

    @Test
    fun `unwired stores leave the row summary off`() {
        val routes = SessionsRoutes(
            registry(),
            TestTranscripts(),
            roots = TranscriptRoots(vanilla = tmp.resolve(".claude")),
        )
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
                    else -> TranscriptLookup.Missing
                }
            },
        )
        val routes = SessionsRoutes(
            registry(),
            transcripts,
            roots = TranscriptRoots(mapOf("codex" to head(own)), vanilla = vanilla),
        )
        val reply = routes.transcript(ALPHA, null, null)
        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(
            json(
                """{"messages":[""" +
                    """{"index":0,"role":"user","text":"the head's copy"}],""" +
                    """"path":"$file",""" +
                    """"moves":[],"unmoved":[],"unparseable_lines":1}""",
            ),
            json(reply.body),
        )
        val missing = routes.transcript(BETA, null, null)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(HttpStatusCode.BadRequest, routes.transcript(ALPHA, "x", null).status)
    }

    @Test
    fun `the transcript page publishes call identities even when results have no local call`() {
        val messages = listOf(
            TranscriptMessage(0, TranscriptRole.ASSISTANT, null, "{}", TranscriptToolUse("Read", false, "first")),
            TranscriptMessage(1, TranscriptRole.ASSISTANT, null, "{}", TranscriptToolUse("Read", false, "second")),
            TranscriptMessage(
                2,
                TranscriptRole.TOOL,
                null,
                "first body",
                TranscriptToolUse(result = true, id = "first"),
            ),
            TranscriptMessage(
                3,
                TranscriptRole.TOOL,
                null,
                "second body",
                TranscriptToolUse(result = true, id = "second"),
            ),
        )
        val transcripts = TestTranscripts(pages = { session, _, _, _ ->
            TranscriptLookup.Found(TranscriptPage(session, "/synthetic/transcript", messages, null, emptyMap()))
        })
        val routes = SessionsRoutes(
            registry(),
            transcripts,
            roots = TranscriptRoots(vanilla = tmp.resolve(".claude")),
        )
        val rows = json(routes.transcript(ALPHA, null, null).body).getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("first", "second", "first", "second"),
            rows.map { it.getValue("tool_use_id").jsonPrimitive.content },
        )
    }

    @Test
    fun `an idle card shows the reply before the person's last message, a running one shows its newest`() {
        val person = TranscriptMessage(5, TranscriptRole.USER, null, "take your time with it")
        val reply = TranscriptMessage(4, TranscriptRole.ASSISTANT, null, "the previous reply")
        val transcripts = object : SessionTranscripts {
            override fun last(sessionId: String, roots: List<Path>, cwd: String?) = person

            override fun tail(sessionId: String, roots: List<Path>, cwd: String?) = SessionTail("gpt-6.1", reply)

            override fun page(
                sessionId: String,
                roots: List<Path>,
                cursor: String?,
                limit: Int,
                cwd: String?,
            ): TranscriptLookup =
                error("a card never pages")

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        Files.writeString(dir.resolve("11.json"), """{"pid":11,"sessionId":"$ALPHA","updatedAt":$NOW}""")
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"sessionId":"$BETA","status":"busy","updatedAt":$NOW}""")
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Unknown }, pidAlive = { true }, clock = { NOW })
        val roots = TranscriptRoots(vanilla = tmp.resolve(".claude"))
        val rows = rowsOf(SessionsRoutes(registry, transcripts, roots = roots))
        fun said(id: String) = rows.getValue(id)["last"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("the previous reply", said(ALPHA))
        assertEquals("take your time with it", said(BETA))
    }

    @Test
    fun `a card names the model a move put it on until a reply comes, then the model of that reply`() {
        val transcripts = object : SessionTranscripts {
            override fun tail(sessionId: String, roots: List<Path>, cwd: String?): SessionTail {
                val at = if (sessionId == ALPHA) 1_000L else 3_000L
                return SessionTail("claude-opus-5-5", TranscriptMessage(4, TranscriptRole.ASSISTANT, at, "a reply"))
            }

            override fun moves(sessionId: String): List<ModelMove> =
                listOf(ModelMove(null, "claude-fable-5-1", "claude", 2_000L, "claude", "claude-opus-5-5"))

            override fun page(
                sessionId: String,
                roots: List<Path>,
                cursor: String?,
                limit: Int,
                cwd: String?,
            ): TranscriptLookup =
                error("a card never pages")

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        Files.writeString(dir.resolve("11.json"), """{"pid":11,"sessionId":"$ALPHA","updatedAt":$NOW}""")
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"sessionId":"$BETA","updatedAt":$NOW}""")
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Unknown }, pidAlive = { true }, clock = { NOW })
        val roots = TranscriptRoots(vanilla = tmp.resolve(".claude"))
        val rows = rowsOf(SessionsRoutes(registry, transcripts, roots = roots))
        assertEquals("claude-fable-5-1", rows.getValue(ALPHA)["model"]!!.jsonPrimitive.content, "moved, no reply since")
        assertEquals("claude-opus-5-5", rows.getValue(BETA)["model"]!!.jsonPrimitive.content, "replied after its move")
    }

    @Test
    fun `a line Claude Code wrote itself after a move does not date the move away`() {
        // what the walk met: the move back landed, then its first turn failed and Claude Code wrote the failure into
        // the conversation. That line names no model, so the newest reply that does is still the one it left on.
        val transcripts = object : SessionTranscripts {
            override fun tail(sessionId: String, roots: List<Path>, cwd: String?): SessionTail = SessionTail(
                model = "gpt-5.6",
                answered = TranscriptMessage(5, TranscriptRole.ASSISTANT, 3_000L, "API error: Not logged in"),
                modelAt = 1_000L,
            )

            override fun moves(sessionId: String): List<ModelMove> =
                listOf(ModelMove(null, "claude-opus-5-5", "claude-splice", 2_000L, "claudex", "gpt-5.6"))

            override fun page(
                sessionId: String,
                roots: List<Path>,
                cursor: String?,
                limit: Int,
                cwd: String?,
            ): TranscriptLookup =
                error("a card never pages")

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        Files.writeString(dir.resolve("11.json"), """{"pid":11,"sessionId":"$ALPHA","updatedAt":$NOW}""")
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Unknown }, pidAlive = { true }, clock = { NOW })
        val roots = TranscriptRoots(vanilla = tmp.resolve(".claude"))
        val rows = rowsOf(SessionsRoutes(registry, transcripts, roots = roots))
        assertEquals("claude-opus-5-5", rows.getValue(ALPHA)["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the transcript page says where the session moved to another model`() {
        val reply = TranscriptMessage(0, TranscriptRole.ASSISTANT, null, "hi", messageId = "m1")
        val transcripts = object : SessionTranscripts {
            override fun page(
                sessionId: String,
                roots: List<Path>,
                cursor: String?,
                limit: Int,
                cwd: String?,
            ): TranscriptLookup =
                TranscriptLookup.Found(TranscriptPage(sessionId, "/x/$ALPHA.jsonl", listOf(reply), null, emptyMap()))

            override fun moves(sessionId: String): List<ModelMove> =
                if (sessionId == ALPHA) {
                    listOf(ModelMove("m1", "gpt-6.1", "gpt", 5L, "claude", "claude-fable-5-1", true))
                } else {
                    emptyList()
                }

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }
        val routes = SessionsRoutes(registry(), transcripts, roots = TranscriptRoots(vanilla = tmp.resolve(".claude")))
        val body = json(routes.transcript(ALPHA, null, null).body)
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """[{"model":"gpt-6.1","command":"gpt","moved_at":5,"from_command":"claude",""" +
                    """"from_model":"claude-fable-5-1","thinking_dropped":true}]""",
            ),
            body.getValue("moves"),
        )
    }

    @Test
    fun `the transcript route reads from the end when asked, and names the cursor of the page before`() {
        val asked = mutableListOf<String?>()
        val transcripts = object : SessionTranscripts {
            override fun page(
                sessionId: String,
                roots: List<Path>,
                cursor: String?,
                limit: Int,
                cwd: String?,
            ): TranscriptLookup =
                error("a read from the end never pages forward")

            override fun pageBefore(
                sessionId: String,
                roots: List<Path>,
                before: String?,
                limit: Int,
                cwd: String?,
            ): TranscriptLookup {
                asked += before
                val newest = TranscriptMessage(7168, TranscriptRole.USER, null, "newest")
                return TranscriptLookup.Found(
                    TranscriptPage(
                        sessionId,
                        "/x/$ALPHA.jsonl",
                        listOf(newest),
                        null,
                        emptyMap(),
                        earlier = if (before == null) "512" else null,
                    ),
                )
            }

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
                SentTexts(null, emptyMap(), ids)
        }
        val routes = SessionsRoutes(
            registry(),
            transcripts,
            roots = TranscriptRoots(vanilla = tmp.resolve(".claude")),
        )
        val newest = json(routes.transcript(ALPHA, null, 20, "end").body)
        assertEquals("512", newest["earlier"]!!.jsonPrimitive.content)
        assertEquals("newest", newest["messages"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val earlier = routes.transcript(ALPHA, null, 20, "512")
        assertEquals(HttpStatusCode.OK, earlier.status)
        val start = json(earlier.body)["earlier"]
        assertTrue(start is kotlinx.serialization.json.JsonNull, "the start of the file has nothing earlier")
        assertEquals(listOf(null, "512"), asked, "end reads from the end, a number is the byte offset itself")
    }

    private fun head(own: Path): SessionHead = SessionHead(transcriptRoot = own)
}

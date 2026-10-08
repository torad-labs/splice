package splice.sessions.http.v4344

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.http.SessionAccountOf
import splice.sessions.http.SessionHistoryRoute
import splice.sessions.http.SessionHistoryRowOf
import splice.sessions.http.SessionRepoNameOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TestTranscripts
import splice.sessions.http.TranscriptRoots
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import java.nio.file.Path

private const val BOTH = "3f2a9c1e-0000-4000-8000-000000000001"
private const val HISTORY_ONLY = "3f2a9c1e-0000-4000-8000-000000000002"
private const val TRANSCRIPT_ONLY = "3f2a9c1e-0000-4000-8000-000000000003"
private const val REGISTRY_ONLY = "3f2a9c1e-0000-4000-8000-000000000004"

class SessionHistoryRouteTest {
    @TempDir lateinit var home: Path

    private fun live(id: String, at: Long): SessionRecord = SessionRecord(
        pid = 71L,
        sessionId = id,
        cwd = "/work/atlas",
        name = null,
        kind = "interactive",
        version = null,
        status = SessionStatus("busy", at),
        startedAt = at - 100,
        updatedAt = at,
        messagingSocketPath = null,
        route = SessionRoute.Head("claudex"),
        availability = SessionAvailability.LIVE,
    )

    private val entries = listOf(
        SessionHistoryEntry(BOTH, "Atlas parser", "/work/atlas", "claudex", 1_000, true, true),
        SessionHistoryEntry(HISTORY_ONLY, "Grok session", "/work/grok", "grok", 900, true, false),
        SessionHistoryEntry(TRANSCRIPT_ONLY, "Unnamed", "/work/other", null, 800, false, true, resumable = false),
    )

    private fun route(
        account: SessionAccountOf = SessionAccountOf { _, _ -> null },
        records: List<SessionRecord> = listOf(live(BOTH, 2_000), live(REGISTRY_ONLY, 1_500)),
        scan: SessionHistoryScan = SessionHistoryScan(entries, skipped = mapOf("nested artifacts" to 1)),
        listingError: String? = null,
        repoOf: SessionRepoNameOf = SessionRepoNameOf { null },
    ): SessionHistoryRoute {
        val registry = object : SessionSource {
            override fun read(): List<SessionRecord> = records
            override fun list(): SessionListing = SessionListing(records, listingError)
        }
        val roots = listOf(SessionHistoryRoot(null, home))
        val source = SessionHistorySource { asked ->
            assertEquals(roots, asked, "the source is given the declared on-disk denominator")
            scan
        }
        val rows = SessionsRoutes(
            registry,
            TestTranscripts(),
            roots = TranscriptRoots(vanilla = home),
            accountOf = account,
        )
        return SessionHistoryRoute(
            registry,
            source,
            roots,
            rows.historyRows,
            repoOf = repoOf,
        )
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `pages every source with stable cursor and overlays a live session once`() {
        val first = route().page(null, null, 2)
        assertEquals(HttpStatusCode.OK, first.status)
        val page = json(first.body)
        val rows = page.getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals(listOf(BOTH, REGISTRY_ONLY), rows.map { it.getValue("session_id").jsonPrimitive.content })
        assertEquals("Atlas parser", rows[0].getValue("name").jsonPrimitive.content)
        assertEquals("live", rows[0].getValue("availability").jsonPrimitive.content)
        assertEquals("history+transcript", rows[0].getValue("source").jsonPrimitive.content)
        assertEquals("registry-only", rows[1].getValue("source").jsonPrimitive.content)
        assertEquals("1", page.getValue("skipped").jsonObject.getValue("nested artifacts").jsonPrimitive.content)
        val next = page.getValue("next").jsonPrimitive.content
        val second = json(route().page(null, next, 2).body)
        assertEquals(
            listOf(HISTORY_ONLY, TRANSCRIPT_ONLY),
            second.getValue("sessions").jsonArray.map { it.jsonObject.getValue("session_id").jsonPrimitive.content },
        )
        assertEquals("null", second.getValue("next").toString())
        assertEquals(
            "false",
            second.getValue("sessions").jsonArray.last().jsonObject.getValue("resumable").jsonPrimitive.content,
        )
    }

    @Test
    fun `duplicate registry files keep the newest registration and source failures remain visible`() {
        val route = route(
            records = listOf(live(BOTH, 2_000), live(BOTH, 1_000)),
            scan = SessionHistoryScan(entries, errors = listOf("projects unreadable")),
            listingError = "registry unreadable",
        )
        val page = json(route.page(null, null, 50).body)
        val rows = page.getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals(1, rows.count { it.getValue("session_id").jsonPrimitive.content == BOTH })
        val newest = rows.first { it.getValue("session_id").jsonPrimitive.content == BOTH }
        assertEquals("2000", newest.getValue("updated_at").jsonPrimitive.content)
        assertEquals(
            listOf("projects unreadable", "registry unreadable"),
            page.getValue("errors").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a cursor continues after its live anchor exits the registry`() {
        val registered = mutableListOf(live(BOTH, 2_000), live(REGISTRY_ONLY, 1_500))
        val route = route(records = registered)
        val first = json(route.page(null, null, 2).body)
        val cursor = first.getValue("next").jsonPrimitive.content
        registered.removeAll { it.sessionId == REGISTRY_ONLY }
        val second = route.page(null, cursor, 2)
        assertEquals(HttpStatusCode.OK, second.status)
        val rows = json(second.body).getValue("sessions").jsonArray
        val ids = rows.map { it.jsonObject.getValue("session_id").jsonPrimitive.content }
        assertEquals(listOf(HISTORY_ONLY, TRANSCRIPT_ONLY), ids)
    }

    @Test
    fun `searches name and repository across completed sessions without registry entries`() {
        val byName = json(route().page("Grok", null, null).body)
        assertEquals(
            HISTORY_ONLY,
            byName.getValue("sessions").jsonArray.single().jsonObject.getValue("session_id").jsonPrimitive.content,
        )
        val byRepo = json(route().page("/work/other", null, null).body)
        assertEquals(
            TRANSCRIPT_ONLY,
            byRepo.getValue("sessions").jsonArray.single().jsonObject.getValue("session_id").jsonPrimitive.content,
        )
        assertEquals(
            "history-only",
            byName.getValue("sessions").jsonArray.single().jsonObject.getValue("source").jsonPrimitive.content,
        )
    }

    @Test
    fun `search finds a linked worktree by its shared repository name`() {
        val route = route(
            repoOf = SessionRepoNameOf { record ->
                if (record.sessionId == TRANSCRIPT_ONLY) "/repos/atlas-main" else null
            },
        )
        val rows = json(route.page("atlas-main", null, null).body).getValue("sessions").jsonArray
        assertEquals(TRANSCRIPT_ONLY, rows.single().jsonObject.getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun `a gone registration keeps the head named by its durable transcript tree`() {
        val gone = live(BOTH, 2_000).copy(route = SessionRoute.Unknown, availability = SessionAvailability.GONE)
        val registry = object : SessionSource {
            override fun read(): List<SessionRecord> = listOf(gone)
            override fun list(): SessionListing = SessionListing(listOf(gone))
        }
        val sessions = SessionsRoutes(registry, TestTranscripts(), roots = TranscriptRoots(vanilla = home))
        val route = SessionHistoryRoute(
            registry,
            SessionHistorySource { SessionHistoryScan(entries) },
            listOf(SessionHistoryRoot(null, home)),
            SessionHistoryRowOf(sessions::historyRow),
        )
        val row = json(route.page("Atlas", null, null).body).getValue("sessions").jsonArray.single().jsonObject
        assertEquals("claudex", row.getValue("head").jsonPrimitive.content)
    }

    @Test
    fun `history attribution snapshots only the selected page once and survives another page snapshot`() {
        val batches = mutableListOf<List<String>>()
        val accounts = object : SessionAccountOf {
            override fun label(head: String?, sessionId: String): String? = error("unprepared account lookup")
            override fun forRecords(records: List<SessionRecord>): SessionAccountOf {
                val ids = records.mapNotNull { it.sessionId }
                batches += ids
                val labels = ids.associateWith { "login-$it" }
                return SessionAccountOf { _, session -> labels[session] }
            }
        }
        val history = route(accounts)
        val first = json(history.page(null, null, 2).body)
        val ids = first.getValue("sessions").jsonArray.map {
            it.jsonObject.getValue("session_id").jsonPrimitive.content
        }
        assertEquals(listOf(BOTH, REGISTRY_ONLY), ids)
        assertEquals(listOf(ids), batches, "one batch for the selected page, not per row or whole history")
        val second = json(history.page(null, first.getValue("next").jsonPrimitive.content, 2).body)
        assertEquals(2, batches.size)
        assertEquals(
            "login-$BOTH",
            first.getValue("sessions").jsonArray[0].jsonObject.getValue("account").jsonPrimitive.content,
        )
        assertEquals(
            "known",
            second.getValue("sessions").jsonArray[0].jsonObject.getValue("account_state").jsonPrimitive.content,
        )
    }

    @Test
    fun `attribution asks for the session id rather than borrowing the head-wide account`() {
        val seen = mutableListOf<String>()
        val route = route(
            SessionAccountOf { _, session ->
                seen += session
                if (session == BOTH) "spare" else null
            },
        )
        val rows = json(route.page(null, null, 2).body).getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals("spare", rows[0].getValue("account").jsonPrimitive.content)
        assertEquals("null", rows[1].getValue("account").toString())
        assertEquals(listOf(BOTH, REGISTRY_ONLY), seen)
        assertTrue(route.page(null, "forged", 2).status == HttpStatusCode.BadRequest)
    }
}

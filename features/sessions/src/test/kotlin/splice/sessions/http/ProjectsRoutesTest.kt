// NEW: V4-131 — the project routes' payloads on ProjectsRoutes directly, and the sessions rows'
// `team` key. A project is a git root seen through the sessions' repos or a team's declared repo;
// today's counts cover the UTC day the row names; files are read only for a root the list reports.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.sessions.query.SessionHead
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path

class ProjectsRoutesTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }

    private fun routes(heads: Map<String, SessionHead>): ProjectsRoutes {
        val teams = TeamSource { rig.store }
        val sessions = SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            heads,
            vanilla = tmp.resolve("vanilla"),
            teams = teams,
        )
        return ProjectsRoutes(rig.registry, heads, RepoOf { sessions.repoOf(it) }, teams, WallClock { AT })
    }

    @Test
    fun `each root is a row with its live sessions, teams and today's turns and dollars`() {
        rig.team()
        val archived = rig.store.upsert(rig.store.teams().single().copy(id = "", name = "old"))
        rig.store.archive(archived.id)
        val codex = rig.head(
            "codex",
            listOf(
                rig.row(AT, BUILDER, input = 1_000_000),
                rig.row(AT, OLD_BUILDER, input = 1_000_000, out = 0),
                rig.row(DAY_START - 1, BUILDER, input = 9),
                rig.row(AT, OUTSIDER, input = 1),
            ),
        )
        val body = rig.json(routes(mapOf("codex" to codex)).list().body)
        val rows = body.getValue("projects").jsonArray.map { it.jsonObject }
        val roots = listOf(rig.repo.toString(), tmp.resolve("elsewhere").toString()).sorted()
        assertEquals(roots, rows.map { it.getValue("id").jsonPrimitive.content })
        val repo = rows.single { it.getValue("id").jsonPrimitive.content == rig.repo.toString() }
        assertEquals(
            rig.json(
                """{"id":"${rig.repo}","root":"${rig.repo}","live_sessions":2,"teams":1,"turns_today":2,""" +
                    """"day_start":$DAY_START,"last_activity":${AT + 2}}""",
            ),
            JsonObject(repo - "cost_today_usd"),
            "yesterday's row and the outsider's are not this repo's today; the archived team is not counted",
        )
        // 2M input at 1.0 and 10 output at 2.0, per million.
        assertEquals(2.00002, repo.getValue("cost_today_usd").jsonPrimitive.double, 1e-9)
        val unpriced = rig.head("codex", listOf(rig.row(AT, BUILDER, input = 5)), rates = null)
        val row = rig.json(routes(mapOf("codex" to unpriced)).project(rig.repo.toString()).body)
        assertEquals("null", row.getValue("cost_today_usd").toString(), "no rate card is no dollar figure, never zero")
        assertEquals(HttpStatusCode.NotFound, routes(emptyMap()).project("/nowhere").status)
    }

    @Test
    fun `files are the repo's instructions and each head's memory, and an unseen root is never read`() {
        rig.team()
        Files.writeString(rig.repo.resolve("CLAUDE.md"), "claude rules")
        Files.writeString(rig.repo.resolve("AGENTS.md"), "agent rules")
        val own = tmp.resolve(".claude-codex")
        val slug = rig.repo.toString().replace(Regex("[^A-Za-z0-9]"), "-")
        val memory = Files.createDirectories(own.resolve("projects/$slug/memory"))
        Files.writeString(memory.resolve("MEMORY.md"), "remember this")
        Files.writeString(memory.resolve("notes.txt"), "not memory")
        Files.writeString(own.resolve("settings.json"), """{"autoMemoryEnabled":true}""")
        val other = tmp.resolve(".claude-other")
        val heads = mapOf(
            "codex" to rig.head("codex", emptyList(), own = own),
            "other" to rig.head("other", emptyList(), own = other),
        )
        val body = rig.json(routes(heads).files(rig.repo.toString()).body)
        assertEquals(
            rig.json(
                """{"id":"${rig.repo}","files":[""" +
                    """{"kind":"instructions","path":"${rig.repo}/CLAUDE.md","head":null,"text":"claude rules"},""" +
                    """{"kind":"instructions","path":"${rig.repo}/AGENTS.md","head":null,"text":"agent rules"},""" +
                    """{"kind":"memory","path":"$memory/MEMORY.md","head":"codex","text":"remember this"}],""" +
                    """"looked_in":["${rig.repo}","$memory","${other.resolve("projects/$slug/memory")}"],""" +
                    """"auto_memory_enabled":true}""",
            ),
            body,
        )
        Files.createDirectories(other)
        Files.writeString(other.resolve("settings.json"), """{"autoMemoryEnabled":false}""")
        val disagreeing = routes(heads).files(rig.repo.toString()).body
        assertFalse(disagreeing.contains("auto_memory_enabled"), "heads that disagree state nothing")
        val refused = routes(heads).files(tmp.toString())
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertEquals("""{"error":"not a project root splice has seen: $tmp"}""", refused.body)
    }

    @Test
    fun `a session bound in several teams reports the earliest created, ties broken by id, not file order`() {
        val slot = """{"id":"b1","role":"builder","head":"codex","session":"$BUILDER"}"""
        val team = { id: String, created: Int ->
            """{"id":"$id","name":"$id","created_epoch_millis":$created,"slots":[$slot]}"""
        }
        Files.createDirectories(tmp.resolve("state"))
        Files.writeString(
            tmp.resolve("state/teams.json"),
            """{"teams":[${team("team-b", 2)},${team("team-a", 2)},${team("team-0", 1)}]}""",
        )
        assertEquals(listOf("team-0", "team-a", "team-b"), rig.store.bindingsOf(BUILDER).map { it.first.id })
        val sessions = SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            vanilla = tmp.resolve("vanilla"),
            teams = TeamSource { rig.store },
        )
        val row = rig.find(rig.json(sessions.sessionsJson()), "sessions", "session_id", BUILDER)
        assertEquals("team-0", row.getValue("team").jsonPrimitive.content)
    }

    @Test
    fun `team chat text is read from the same transcript trees the transcript route searches`() {
        val file = tmp.resolve("vanilla/projects/-w/$LEAD.jsonl")
        Files.createDirectories(file.parent)
        val call = """{"type":"tool_use","id":"toolu_a","name":"SendMessage","input":{"to":"b","message":"go"}}"""
        Files.writeString(file, """{"type":"assistant","message":{"id":"m","content":[$call]}}""" + "\n")
        val transcripts = TestTranscripts(
            sends = { session, roots, ids ->
                assertEquals(LEAD, session)
                assertEquals(listOf(tmp.resolve("vanilla")), roots)
                SentTexts(file.toString(), mapOf("toolu_a" to "go"), ids - "toolu_a")
            },
        )
        val sessions = SessionsRoutes(rig.registry, transcripts, vanilla = tmp.resolve("vanilla"))
        val sent = sessions.sentTexts(LEAD, null, setOf("toolu_a", "toolu_b"))
        assertEquals(file.toString(), sent.path)
        assertEquals(mapOf("toolu_a" to "go"), sent.texts)
        assertEquals(setOf("toolu_b"), sent.missing)
    }

    @Test
    fun `a session row carries the id of the unarchived team it is bound in, else null`() {
        val team = rig.team()
        val sessions = SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            vanilla = tmp.resolve("vanilla"),
            teams = TeamSource { rig.store },
        )
        val teamOf = { id: String ->
            rig.json(sessions.sessionsJson()).getValue("sessions").jsonArray.map { it.jsonObject }
                .single { it.getValue("session_id").jsonPrimitive.content == id }.getValue("team").toString()
        }
        assertEquals("\"${team.id}\"", teamOf(BUILDER))
        assertEquals("null", teamOf(OUTSIDER))
        rig.store.archive(team.id)
        assertEquals("null", teamOf(BUILDER), "an archived team binds nothing")
    }
}

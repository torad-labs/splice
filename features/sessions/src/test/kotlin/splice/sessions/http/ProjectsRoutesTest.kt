// NEW: V4-131 — the project routes' payloads on ProjectsRoutes directly, and the sessions rows'
// `team` key. A project is a git root seen through the sessions' repos or a team's declared repo;
// files are read only for a root the list reports.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.compaction.CompactionProjectConfig
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.sessions.query.SessionHead
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path

class ProjectsRoutesTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }

    private fun routes(heads: Map<String, SessionHead>): ProjectsRoutes {
        Files.createDirectories(rig.repo.resolve(".git"))
        val teams = TeamSource { rig.store }
        val sessions = SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            roots = TranscriptRoots(heads, vanilla = tmp.resolve("vanilla")),
            teams = teams,
        )
        return ProjectsRoutes(
            rig.registry,
            heads,
            ProjectLookup(ProjectOf(sessions.projects::of), ProjectAt { sessions.projects.at(it, null) }),
            teams,
        )
    }

    @Test
    fun `each git root a session runs in is a row, and an unseen root is a 404`() {
        val rows = rig.json(routes(emptyMap()).list().body).getValue("projects").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(rig.repo.toString()),
            rows.map { it.getValue("id").jsonPrimitive.content },
            "a registry cwd outside git does not make a project",
        )
        assertEquals(HttpStatusCode.NotFound, routes(emptyMap()).files("/nowhere").status)
    }

    private fun sessionRoutes(heads: Map<String, SessionHead>, config: ConfigService) = SessionsRoutes(
        rig.registry,
        TestTranscripts(),
        roots = TranscriptRoots(heads, vanilla = tmp.resolve("vanilla")),
        settings = ConfigSessionSettings(config),
    )

    // FEATURES.md 4.14's "compaction scope and effective instructions": the rules core says a
    // compaction in this repo resolves to (a project rule shadows the global one).
    @Test
    fun `the row names the compaction rules for its repo`() {
        rig.team()
        Files.createDirectories(rig.repo.resolve(".git"))
        Files.createDirectory(tmp.resolve("elsewhere/.git"))
        val heads = mapOf("codex" to rig.head("codex", emptyList()), "grok" to rig.head("grok", emptyList()))
        val config = ConfigService(
            StatePaths(baseOverride = tmp.resolve("state")),
            headOverrides = mapOf("statuslineGitRoots" to tmp.toString()),
            envReader = EnvReader { null },
            log = { },
        )
        val sessions = sessionRoutes(heads, config)
        val table = CompactionInstructions(
            CompactionConfig(
                instructions = "global text",
                project = listOf(CompactionProjectConfig(rig.repo.toString(), instructions = "repo text")),
            ),
            tmp,
            log = { },
        )
        fun routes(wired: CompactionInstructions?) = ProjectsRoutes(
            rig.registry,
            heads,
            ProjectLookup(ProjectOf(sessions.projects::of), ProjectAt { sessions.projects.at(it, null) }),
            TeamSource { rig.store },
            CompactionSource { wired },
        )
        val repo = rig.repo.toRealPath().toString()
        fun listed(served: ProjectsRoutes, id: String) = rig.json(served.list().body).getValue("projects").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
        val row = listed(routes(table), rig.repo.toString())
        assertEquals(
            Json.parseToJsonElement("""[{"scope":"project","source":"project:$repo","chars":9}]"""),
            row.getValue("compaction"),
            "the repo's project rule shadows the global one, and its length is live",
        )
        val elsewhere = listed(routes(table), tmp.resolve("elsewhere").toString())
        assertEquals(
            Json.parseToJsonElement("""[{"scope":"global","source":"global","chars":11}]"""),
            elsewhere.getValue("compaction"),
            "outside every project rule the global one is what a compaction resolves to",
        )
        val unwired = listed(routes(null), rig.repo.toString())
        assertEquals("null", unwired.getValue("compaction").toString(), "a table never wired is null, never []")
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
        val other = tmp.resolve(".claude-other")
        val heads = mapOf(
            "codex" to rig.head("codex", emptyList(), own = own),
            "other" to rig.head("other", emptyList(), own = other),
        )
        val body = rig.json(routes(heads).files(rig.repo.toString()).body)
        assertEquals(
            rig.json(
                """{"files":[""" +
                    """{"kind":"instructions","path":"${rig.repo}/CLAUDE.md","head":null,"text":"claude rules"},""" +
                    """{"kind":"instructions","path":"${rig.repo}/AGENTS.md","head":null,"text":"agent rules"},""" +
                    """{"kind":"memory","path":"$memory/MEMORY.md","head":"codex","text":"remember this"}]}""",
            ),
            body,
        )
        val refused = routes(heads).files(tmp.toString())
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertEquals("""{"error":"not a project root splice has seen: $tmp"}""", refused.body)
    }

    @Test
    fun `a session bound in several teams reports the earliest created, ties broken by id, not file order`() {
        val slot = """{"id":"b1","role":"builder","head":"codex","session":"$BUILDER"}"""
        val team = { id: String, created: Int ->
            """{"id":"$id","name":"atlas $id","created_epoch_millis":$created,"slots":[$slot]}"""
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
            roots = TranscriptRoots(vanilla = tmp.resolve("vanilla")),
            teams = TeamSource { rig.store },
        )
        val row = rig.find(rig.json(sessions.sessionsJson()), "sessions", "session_id", BUILDER)
        assertEquals("team-0", row.getValue("team").jsonPrimitive.content)
        // the card names the team he reads, never its id (Marlin, move check: a bound session showed no team at all)
        assertEquals("atlas team-0", row.getValue("team_name").jsonPrimitive.content)
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
        val sessions = SessionsRoutes(
            rig.registry,
            transcripts,
            roots = TranscriptRoots(vanilla = tmp.resolve("vanilla")),
        )
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
            roots = TranscriptRoots(vanilla = tmp.resolve("vanilla")),
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

    // Marlin's ruling (Oct 11): with a Projects folder set, the project is the folder directly under it, and the
    // session rows, /api/projects and the Teams slots all print the SAME object for it.
    private fun foldersOver(root: Path): SessionsRoutes {
        Files.createDirectories(rig.repo.resolve(".git"))
        val folders = object : SessionSettings {
            override fun transcriptView(): Boolean = true

            override fun gitRoots(head: String?): List<String> = emptyList()

            override fun projectFolders(head: String?): List<String> = listOf(root.toString())
        }
        return SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            roots = TranscriptRoots(vanilla = tmp.resolve("vanilla")),
            teams = TeamSource { rig.store },
            settings = folders,
        )
    }

    private fun lookupOf(sessions: SessionsRoutes): ProjectLookup =
        ProjectLookup(ProjectOf(sessions.projects::of), ProjectAt { sessions.projects.at(it, null) })

    private fun projectOnRow(sessions: SessionsRoutes) =
        rig.find(rig.json(sessions.sessionsJson()), "sessions", "session_id", BUILDER).getValue("project").jsonObject

    @Test
    fun `the row says where the session is from home, and the whole path outside it`() {
        val row = { sessions: SessionsRoutes ->
            rig.find(rig.json(sessions.sessionsJson()), "sessions", "session_id", BUILDER)
        }
        val inHome = UserHome.within(tmp.toRealPath()) { row(foldersOver(tmp.toRealPath())) }
        assertEquals("~/repo", inHome.getValue("place").jsonPrimitive.content)
        val elsewhere = UserHome.within(tmp.toRealPath().resolve("other")) { row(foldersOver(tmp.toRealPath())) }
        assertEquals(tmp.toRealPath().resolve("repo").toString(), elsewhere.getValue("place").jsonPrimitive.content)
    }

    @Test
    fun `with a Projects folder the session row names the folder directly under it`() {
        val onRow = projectOnRow(foldersOver(tmp.toRealPath()))
        assertEquals(tmp.toRealPath().resolve("repo").toString(), onRow.getValue("id").jsonPrimitive.content)
        assertEquals("repo", onRow.getValue("name").jsonPrimitive.content)
        assertEquals("folder", onRow.getValue("source").jsonPrimitive.content)
    }

    @Test
    fun `with a Projects folder the project list holds the folder projects`() {
        val sessions = foldersOver(tmp.toRealPath())
        val expectedId = tmp.toRealPath().resolve("repo").toString()
        val projects = ProjectsRoutes(
            rig.registry,
            emptyMap(),
            lookupOf(sessions),
            TeamSource { rig.store },
        )
        val listed = rig.json(projects.list().body).getValue("projects").jsonArray.map { it.jsonObject }
        assertEquals(
            setOf(expectedId, tmp.toRealPath().resolve("elsewhere").toString()),
            listed.map { it.getValue("id").jsonPrimitive.content }.toSet(),
        )
        val own = listed.single { it.getValue("id").jsonPrimitive.content == expectedId }
        assertEquals("folder", own.getValue("source").jsonPrimitive.content)
        assertEquals(tmp.toRealPath().toString(), own.getValue("folder").jsonPrimitive.content)
    }

    @Test
    fun `with a Projects folder the team and its slot print the same project as the session row`() {
        val team = rig.team()
        val sessions = foldersOver(tmp.toRealPath())
        val onRow = projectOnRow(sessions)
        val teamsRoutes = TeamsRoutes(
            TeamSource { rig.store },
            emptyMap(),
            rig.registry,
            ActivitySource { rig.stores },
            SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            lookup = lookupOf(sessions),
        )
        val shown = rig.json(teamsRoutes.list().body).getValue("teams").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == team.id }
        assertEquals(onRow, shown.getValue("project").jsonObject)
        assertEquals(
            onRow,
            shown.getValue("slots").jsonArray.map { it.jsonObject }.first().getValue("project").jsonObject,
        )
    }
}

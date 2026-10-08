package splice.sessions.http.v4363

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.http.ProjectSessions
import splice.sessions.http.ProjectsRoutes
import splice.sessions.http.RepoOf
import splice.sessions.http.SessionHistoryRoute
import splice.sessions.http.SessionHistoryRowOf
import splice.sessions.http.SessionRepoNameOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.http.TestTranscripts
import splice.sessions.http.TranscriptRoots
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import java.nio.file.Files
import java.nio.file.Path

class HistoryProjectRoutesTest {
    @Test
    fun `a transcript-only session makes its git root a project with readable instructions`(@TempDir tmp: Path) {
        val repo = Files.createDirectories(tmp.resolve("tally"))
        Files.createDirectory(repo.resolve(".git"))
        val nested = Files.createDirectories(repo.resolve("src/deep"))
        Files.writeString(repo.resolve("CLAUDE.md"), "repo instructions")
        val outside = Files.createDirectories(tmp.resolve("scratch"))
        val id = "12345678-1234-4234-8234-123456789abc"
        val records = object : SessionSource {
            override fun read(): List<SessionRecord> = emptyList()
            override fun list(): SessionListing = SessionListing(emptyList())
        }
        val roots = listOf(SessionHistoryRoot(null, tmp.resolve(".claude")))
        val source = historySource(roots, id, nested, outside)
        val sessions = SessionsRoutes(
            records,
            TestTranscripts(),
            roots = TranscriptRoots(vanilla = tmp.resolve(".claude")),
        )
        val repoOf = RepoOf { sessions.repoOf(it) }
        val historical = SessionHistoryRoute(
            records,
            source,
            roots,
            SessionHistoryRowOf(sessions::historyRow),
            repoOf = SessionRepoNameOf { sessions.repoOf(it)?.root },
        )
        val listed = Json.parseToJsonElement(historical.page(null, null, 20).body).jsonObject
            .getValue("sessions").jsonArray.map { it.jsonObject }
        val fromHistory = listed.single { it.getValue("session_id").jsonPrimitive.content == id }
        assertEquals(repo.toString(), fromHistory.getValue("repo").jsonObject.getValue("root").jsonPrimitive.content)

        val projects = ProjectsRoutes(ProjectSessions(records, source, roots), emptyMap(), repoOf, TeamSource { null })
        val rootsSeen = Json.parseToJsonElement(projects.list().body).jsonObject.getValue("projects")
            .jsonArray.map { it.jsonObject.getValue("root").jsonPrimitive.content }
        assertTrue(repo.toString() in rootsSeen, "the Sessions listing names a repo missing from Projects")
        assertTrue(outside.toString() !in rootsSeen, "a cwd outside git is not a project root")
        assertEquals(HttpStatusCode.OK, projects.project(repo.toString()).status)
        val files = projects.files(repo.toString())
        assertEquals(HttpStatusCode.OK, files.status)
        assertTrue(files.body.contains("repo instructions"))
        assertEquals(HttpStatusCode.NotFound, projects.files(outside.toString()).status)
        assertEquals(HttpStatusCode.NotFound, projects.project(tmp.resolve("unseen").toString()).status)
    }

    private fun historySource(roots: List<SessionHistoryRoot>, id: String, nested: Path, outside: Path) =
        SessionHistorySource { asked ->
            assertEquals(roots, asked)
            SessionHistoryScan(
                listOf(
                    SessionHistoryEntry(id, "Tally session", nested.toString(), null, 1_000, true, true),
                    SessionHistoryEntry(
                        "87654321-1234-4234-8234-123456789abc",
                        "Scratch",
                        outside.toString(),
                        null,
                        900,
                        true,
                        true,
                    ),
                ),
            )
        }
}

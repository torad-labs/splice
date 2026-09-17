import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.SessionProject
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.MaterializeSpec
import splice.core.util.ElapsedClock
import java.nio.file.Files
import java.nio.file.Path

class SessionProjectTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `live session registry cwd wins over the transcript fallback`() {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        val projects = Files.createDirectories(tmp.resolve("projects"))
        val live = tmp.resolve("live-project").toAbsolutePath()
        val transcript = tmp.resolve("old-project").toAbsolutePath()
        Files.writeString(
            sessions.resolve("worker.json"),
            """{"sessionId":"session-1","cwd":"$live"}""",
        )
        val encoded = Files.createDirectories(projects.resolve("-old-project"))
        Files.writeString(
            encoded.resolve("session-1.jsonl"),
            """{"sessionId":"session-1","cwd":"$transcript"}
""",
        )

        assertEquals(live.normalize(), SessionProject(sessions, projects).projectFor("session-1"))
    }

    @Test
    fun `transcript cwd is used when the live registry has no matching session`() {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        val projects = Files.createDirectories(tmp.resolve("projects"))
        val cwd = tmp.resolve("fallback-project").toAbsolutePath()
        val encoded = Files.createDirectories(projects.resolve("-fallback-project"))
        Files.writeString(
            encoded.resolve("session-2.jsonl"),
            """{"sessionId":"other","cwd":"${tmp.toAbsolutePath()}"}
{"sessionId":"session-2","cwd":"$cwd"}
""",
        )

        assertEquals(cwd.normalize(), SessionProject(sessions, projects).projectFor("session-2"))
    }

    @Test
    fun `unknown malformed and relative projects degrade to no project`() {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        val projects = Files.createDirectories(tmp.resolve("projects"))
        Files.writeString(sessions.resolve("broken.json"), "not json")
        Files.writeString(
            sessions.resolve("relative.json"),
            """{"sessionId":"relative","cwd":"not/absolute"}""",
        )

        val resolver = SessionProject(sessions, projects)
        assertNull(resolver.projectFor("missing"))
        assertNull(resolver.projectFor("relative"))
        assertNull(resolver.projectFor("../escape"))
        assertNull(resolver.projectFor(null))
    }

    @Test
    fun `successful lookup remains stable after registry disappearance`() {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        val projects = Files.createDirectories(tmp.resolve("projects"))
        val cwd = tmp.resolve("cached-project").toAbsolutePath()
        val registry = sessions.resolve("worker.json")
        Files.writeString(registry, """{"sessionId":"cached","cwd":"$cwd"}""")
        var now = 0L
        val resolver = SessionProject(sessions, projects, ElapsedClock { now })

        assertEquals(cwd.normalize(), resolver.projectFor("cached"))
        Files.delete(registry)
        now = 60_000L
        assertEquals(cwd.normalize(), resolver.projectFor("cached"))
    }

    @Test
    fun `negative lookup avoids rescans until its original TTL expires`() {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        val projects = Files.createDirectories(tmp.resolve("projects"))
        val cwd = tmp.resolve("new-project").toAbsolutePath()
        var now = 0L
        val resolver = SessionProject(sessions, projects, ElapsedClock { now })

        assertNull(resolver.projectFor("new"))
        Files.writeString(sessions.resolve("new.json"), """{"sessionId":"new","cwd":"$cwd"}""")
        now = 4_999L
        assertNull(resolver.projectFor("new"), "a rescan would already see the new registry entry")
        now = 5_000L
        assertEquals(cwd.normalize(), resolver.projectFor("new"), "a cache hit must not renew the TTL")
    }

    @Test
    fun `failed directory lookup can recover through a new transcript after the TTL`() {
        val sessions = tmp.resolve("sessions")
        val projects = tmp.resolve("projects")
        val cwd = tmp.resolve("headless-project").toAbsolutePath()
        var now = 0L
        val resolver = SessionProject(sessions, projects, ElapsedClock { now })
        assertNull(resolver.projectFor("headless"))

        val encoded = Files.createDirectories(projects.resolve("-headless-project"))
        Files.writeString(encoded.resolve("headless.jsonl"), """{"sessionId":"headless","cwd":"$cwd"}""")
        assertNull(resolver.projectFor("headless"))
        now = 5_000L
        assertEquals(cwd.normalize(), resolver.projectFor("headless"))
    }

    @Test
    fun `negative cache evicts its oldest entry at capacity without forgetting a positive lookup`() {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        val projects = Files.createDirectories(tmp.resolve("projects"))
        val cwd = tmp.resolve("cached-project").toAbsolutePath()
        val registry = sessions.resolve("worker.json")
        Files.writeString(registry, """{"sessionId":"cached","cwd":"$cwd"}""")
        val resolver = SessionProject(sessions, projects, ElapsedClock { 0L })
        assertEquals(cwd.normalize(), resolver.projectFor("cached"))
        Files.delete(registry)
        assertNull(resolver.projectFor("oldest"))
        repeat(1_024) { assertNull(resolver.projectFor("missing-$it")) }

        Files.writeString(registry, """{"sessionId":"oldest","cwd":"$cwd"}""")
        assertEquals(cwd.normalize(), resolver.projectFor("oldest"))
        assertEquals(cwd.normalize(), resolver.projectFor("cached"))
    }

    // V4-64/V4-65 (shared transcripts): a session started on a head whose projects/ was still a
    // REAL directory is migrated into the global projects dir when the head materializes with
    // `projects` shared. The daemon's SessionProject reads the GLOBAL dirs, so after the migration
    // — and with NO registry entry, the headless case — it must resolve that session's cwd from the
    // migrated transcript: compaction instructions follow the session to whichever head resumes it.
    @Test
    fun `a transcript migrated out of a head projects dir resolves its cwd with no registry entry`() {
        val home = tmp
        val globalSessions = Files.createDirectories(home.resolve(".claude/sessions"))
        val globalProjects = Files.createDirectories(home.resolve(".claude/projects"))
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val encoded = cwd.toString().replace(Regex("[^A-Za-z0-9]"), "-")
        val headProjects = home.resolve(".claude-a/projects")
        Files.createDirectories(headProjects.resolve(encoded))
        Files.writeString(
            headProjects.resolve(encoded).resolve("migrated-1.jsonl"),
            """{"type":"user","sessionId":"migrated-1","cwd":"$cwd","message":{"role":"user","content":"hi"}}
""",
        )
        val policy = ClaudePolicy(share = setOf("projects"), isolate = emptySet())

        ClaudeConfigMaterializer(home).materialize(
            MaterializeSpec(home.resolve(".claude-a"), policy, listOf("m1"), "m1", buildJsonObject { }, "statusline"),
        )

        assertTrue(Files.isSymbolicLink(headProjects), "the head's real projects dir must become the link")
        assertTrue(
            Files.isRegularFile(globalProjects.resolve(encoded).resolve("migrated-1.jsonl")),
            "the transcript must now live under the global projects dir",
        )
        assertEquals(cwd.normalize(), SessionProject(globalSessions, globalProjects).projectFor("migrated-1"))
    }
}

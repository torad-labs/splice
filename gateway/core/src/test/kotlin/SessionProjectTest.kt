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
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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

    // V4-168 (2026-09-19), restoring the V4-64 pin that V4-115 had inverted: a head whose policy
    // shares `projects` has its real tree migrated into ~/.claude/projects and replaced by a
    // symlink, and the resolver reads the transcript through BOTH spellings — the head's own (a
    // live session keeps writing there) and the vanilla one (every other head's picker lists it).
    @Test
    fun `a head transcript is reachable through the head tree and the vanilla tree after materialize`() {
        val home = tmp
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val encoded = cwd.toString().replace(Regex("[^A-Za-z0-9]"), "-")
        val head = home.resolve(".claude-a")
        val headSessions = Files.createDirectories(head.resolve("sessions"))
        val transcript = head.resolve("projects").resolve(encoded).resolve("mine-1.jsonl")
        Files.createDirectories(transcript.parent)
        Files.writeString(
            transcript,
            """{"type":"user","sessionId":"mine-1","cwd":"$cwd","message":{"role":"user","content":"hi"}}
""",
        )
        val policy = ClaudePolicy(share = setOf("projects"), isolate = emptySet())

        ClaudeConfigMaterializer(home).materialize(
            MaterializeSpec(head, policy, listOf("m1"), "m1", buildJsonObject { }, "statusline"),
        )

        assertTrue(Files.isSymbolicLink(head.resolve("projects")), "the head's projects dir is the shared tree")
        assertTrue(Files.isRegularFile(transcript), "the head's own spelling still reaches its transcript")
        val vanilla = home.resolve(".claude/projects").resolve(encoded).resolve("mine-1.jsonl")
        assertTrue(Files.isRegularFile(vanilla, NOFOLLOW_LINKS), "and the vanilla tree now holds it too")
        assertEquals(cwd.normalize(), SessionProject(headSessions, head.resolve("projects")).projectFor("mine-1"))
        val vanillaTree = home.resolve(".claude/projects")
        assertEquals(cwd.normalize(), SessionProject(headSessions, vanillaTree).projectFor("mine-1"))
    }
}

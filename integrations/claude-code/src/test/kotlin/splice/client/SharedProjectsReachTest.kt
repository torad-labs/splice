// V4-168 (2026-09-19), restoring the V4-64 pin that V4-115 had inverted: a head whose policy shares
// `projects` has its real tree migrated into ~/.claude/projects and replaced by a symlink, and
// SessionProject reads the transcript through BOTH spellings — the head's own (a live session keeps
// writing there) and the vanilla one (every other head's picker lists it).
//
// SPLIT OUT of gateway/core/src/test/kotlin/SessionProjectTest.kt by the :client extraction
// (restructure plan §2.3, §2.6's split shape). The other seven arms exercise SessionProject alone
// and stay with it in :core; this one drives ClaudeConfigMaterializer, which is :client's, and a
// :core test may not reach :client — the module law allows :core no internal dependency at all.
// The responsibility it exercises is the materializer's shared-`projects` migration, so it is placed
// with the materializer. Byte-identical to the arm it came from.
package splice.client

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.SessionProject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class SharedProjectsReachTest {

    @TempDir
    lateinit var tmp: Path

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

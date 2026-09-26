// NEW: V4-183 — a bare -c resolves to THIS head's newest session in the cwd, or to a new session
// said out loud; a named resume, a launch without -c, and a shim without a cwd are left alone.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HeadBoundedContinueTest {

    private fun owned(home: Path, id: String, cwd: Path) {
        val transcript = home.resolve("projects/-work/$id.jsonl")
        Files.createDirectories(transcript.parent)
        Files.writeString(transcript, "")
        SessionOwnership(home.resolve(".claude-codex")).record(id, cwd.toString(), transcript)
    }

    @Test
    fun `-c becomes --resume of this head's newest session in the cwd`(@TempDir home: Path) {
        val cwd = Files.createDirectories(home.resolve("work"))
        owned(home, "older-session", cwd)
        owned(home, "newer-session", cwd)

        val resolved = HeadBoundedContinue()
            .resolve(home.resolve(".claude-codex"), listOf("-c", "-p", "hi"), cwd.toString())

        assertEquals(listOf("-p", "hi", "--resume", "newer-session"), resolved.args)
        assertNull(resolved.warning)
    }

    @Test
    fun `--continue with no session of this head in the cwd starts a new one and says so`(@TempDir home: Path) {
        val cwd = Files.createDirectories(home.resolve("work"))
        owned(home, "elsewhere", Files.createDirectories(home.resolve("other")))

        val resolved = HeadBoundedContinue()
            .resolve(home.resolve(".claude-codex"), listOf("--continue"), cwd.toString())

        assertEquals(emptyList<String>(), resolved.args, "the client's own -c would continue a foreign session")
        assertTrue(resolved.warning.orEmpty().startsWith("no session of this head in $cwd"), resolved.warning)
    }

    @Test
    fun `a named resume, a launch without -c, and a shim without a cwd are left untouched`(@TempDir home: Path) {
        val cwd = Files.createDirectories(home.resolve("work"))
        owned(home, "mine", cwd)
        val bounded = HeadBoundedContinue()
        val ownTree = home.resolve(".claude-codex")

        val untouched = listOf(
            listOf("-c", "-r", "other"),
            listOf("--continue", "--resume=other"),
            listOf("-p", "hi"),
            listOf("-r"),
        )
        untouched.forEach { args ->
            val resolved = bounded.resolve(ownTree, args, cwd.toString())
            assertEquals(args, resolved.args, "$args is the client's own business")
            assertNull(resolved.warning)
        }

        val noCwd = bounded.resolve(ownTree, listOf("-c"), null)
        assertEquals(listOf("-c"), noCwd.args, "an old shim keeps the client's -c, and is named as the fix")
        assertTrue(noCwd.warning.orEmpty().contains("splice install"), noCwd.warning)
    }
}

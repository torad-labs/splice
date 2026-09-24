// A registered session joins a head only when splice LAUNCHED it (SPLICE=1 beside a local
// ANTHROPIC_BASE_URL); a hand-started claude pointed at a head port is not splice's launch. The
// reading is a SessionRoute: an environment READ without SPLICE=1 is DIRECT, and everything splice
// cannot place — an unreadable or empty environment, a splice launch no head owns — is UNKNOWN.
package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProcessEnvironmentTest {

    private val heads = HeadOfPort { port -> mapOf(3099 to "codex", 3101 to "claudex")[port] }

    private fun environ(root: Path, pid: Long, vararg entries: String): Path {
        val dir = Files.createDirectories(root.resolve(pid.toString()))
        return Files.write(dir.resolve("environ"), entries.joinToString("\u0000").toByteArray())
    }

    /** A credential entry whose bytes are NOT valid UTF-8: decoding the block as one string would
     *  fail (Files.readString throws MalformedInputException), so a resolved head proves the
     *  foreign entry was skipped unread and never decoded. Also: a key that is only a prefix of a
     *  wanted one, and a wanted key at the very end without a trailing NUL. */
    @Test
    fun `a foreign entry is never decoded and prefix look-alikes are not keys`(@TempDir root: Path) {
        val dir = Files.createDirectories(root.resolve("21"))
        val junk = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xc0.toByte())
        val secret = "API_KEY=".toByteArray() + junk + "!".toByteArray()
        val block = secret + byteArrayOf(0) + "SPLICEX=1".toByteArray() + byteArrayOf(0) +
            "SPLIC=1".toByteArray() + byteArrayOf(0) + "SPLICE=1".toByteArray() + byteArrayOf(0) +
            "ANTHROPIC_BASE_URL=http://127.0.0.1:3101".toByteArray()
        Files.write(dir.resolve("environ"), block)
        assertEquals(SessionRoute.Head("claudex"), ProcessEnvironment(root).route(21, heads))
        assertEquals(SessionRoute.Unknown, ProcessEnvironment(root).route(22, heads), "no such process: not read")
    }

    @Test
    fun `only a splice-launched process against a local head joins`(@TempDir root: Path) {
        environ(root, 11, "SPLICE=1", "ANTHROPIC_BASE_URL=http://127.0.0.1:3099", "SECRET_TOKEN=never-read")
        environ(root, 12, "ANTHROPIC_BASE_URL=http://127.0.0.1:3099")
        environ(root, 13, "SPLICE=1", "ANTHROPIC_BASE_URL=https://api.example.com")
        environ(root, 14, "SPLICE=0", "ANTHROPIC_BASE_URL=http://127.0.0.1:3099")
        val env = ProcessEnvironment(root)
        assertEquals(SessionRoute.Head("codex"), env.route(11, heads))
        assertEquals(SessionRoute.Direct, env.route(12, heads), "no SPLICE marker: a manual launch, whatever its URL")
        assertEquals(SessionRoute.Unknown, env.route(13, heads), "splice's launch, but not against a local head")
        assertEquals(SessionRoute.Direct, env.route(14, heads), "SPLICE=0 is not SPLICE=1")
        assertEquals(SessionRoute.Unknown, env.route(99, heads), "no such process reads as no environment")
    }

    @Test
    fun `direct needs an environment that was READ, unknown is everything splice cannot place`(@TempDir root: Path) {
        environ(root, 31, "HOME=/home/me", "ANTHROPIC_API_KEY=never-read")
        environ(root, 32, "SPLICE=1", "ANTHROPIC_BASE_URL=http://127.0.0.1:4000")
        environ(root, 33)
        val unreadable = environ(root, 34, "HOME=/home/me")
        Files.setPosixFilePermissions(unreadable, emptySet())
        Files.createDirectories(root.resolve("35").resolve("environ"))
        val env = ProcessEnvironment(root)
        assertEquals(SessionRoute.Direct, env.route(31, heads), "read, no SPLICE=1: talks to its provider directly")
        assertEquals(SessionRoute.Unknown, env.route(32, heads), "SPLICE=1 on a port no head owns")
        assertEquals(SessionRoute.Unknown, env.route(33, heads), "an empty environment is no evidence of anything")
        assertEquals(SessionRoute.Unknown, env.route(34, heads), "another user's process: permission denied")
        assertEquals(SessionRoute.Unknown, env.route(35, heads), "a read that fails is not a read")
    }
}

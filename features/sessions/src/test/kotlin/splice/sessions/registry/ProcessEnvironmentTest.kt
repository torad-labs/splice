// A registered session joins a head by the LAUNCH splice made: the launcher's roster record (pid, birth, head,
// base URL), checked against the head that owns the declared port. Nothing is inferred from a process environment;
// it is read only to tell a session splice never launched (DIRECT) from one it cannot place (UNKNOWN).
package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.process.LaunchOwners
import splice.core.process.LaunchProcess
import splice.core.process.LaunchProcessIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

class ProcessEnvironmentTest {
    private val heads = HeadOfPort { port -> mapOf(3099 to "codex", 3101 to "claudex")[port] }
    private val birth = Instant.parse("2026-01-01T00:00:00Z")

    private fun roster(root: Path, born: Instant = birth) =
        LaunchOwners(root.resolve("state"), LaunchProcessIdentity { LaunchProcess(born) })

    private fun environ(root: Path, pid: Long, vararg entries: String): Path {
        val dir = Files.createDirectories(root.resolve("proc").resolve(pid.toString()))
        return Files.write(dir.resolve("environ"), entries.joinToString("\u0000").toByteArray())
    }

    private fun environment(root: Path, owners: LaunchOwners = roster(root)) =
        ProcessEnvironment(root.resolve("proc"), owners, DarwinPs(here = false))

    @Test
    fun `a session joins the head its launch declared, whatever its environment says`(@TempDir root: Path) {
        val owners = roster(root)
        owners.write(41, "claudex", "http://127.0.0.1:3101", "session", "other")
        environ(root, 41, "HOME=/home/me")
        assertEquals(SessionRoute.Head("claudex"), environment(root, owners).route(41, heads))
    }

    @Test
    fun `a launch whose port a different head now owns, or no head owns, stays unknown`(@TempDir root: Path) {
        val owners = roster(root)
        owners.write(41, "codex", "http://127.0.0.1:3101", "session", "other")
        owners.write(42, "claudex", "http://127.0.0.1:4000", "session", "other")
        owners.write(43, "claudex", "https://api.example.com", "session", "other")
        val env = environment(root, owners)
        assertEquals(SessionRoute.Unknown, env.route(41, heads), "the declared head is not the one on that port")
        assertEquals(SessionRoute.Unknown, env.route(42, heads), "no head owns the port")
        assertEquals(SessionRoute.Unknown, env.route(43, heads), "not a local head")
    }

    @Test
    fun `a record of a login, or of another process with the same pid, never names a head`(@TempDir root: Path) {
        roster(root).write(51, "claudex", "http://127.0.0.1:3101", "login", "other")
        roster(root).write(52, "claudex", "http://127.0.0.1:3101", "session", "other")
        val reused = environment(root, roster(root, born = Instant.parse("2026-02-02T00:00:00Z")))
        assertEquals(SessionRoute.Unknown, environment(root).route(51, heads), "a login is not a session")
        assertEquals(SessionRoute.Unknown, reused.route(52, heads), "a reused pid has another birth")
    }

    @Test
    fun `a splice marker without a launch record is not placed on a head`(@TempDir root: Path) {
        environ(root, 11, "SPLICE=1", "ANTHROPIC_BASE_URL=http://127.0.0.1:3099", "SECRET_TOKEN=never-read")
        assertEquals(SessionRoute.Unknown, environment(root).route(11, heads), "the environment is no launch")
    }

    @Test
    fun `direct needs an environment that was READ, unknown is everything splice cannot place`(@TempDir root: Path) {
        val junk = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xc0.toByte())
        val dir = Files.createDirectories(root.resolve("proc").resolve("21"))
        // A credential entry that is not valid UTF-8: reading Direct proves it was skipped unread, never decoded.
        val block = "API_KEY=".toByteArray() + junk + byteArrayOf(0) + "SPLICEX=1".toByteArray()
        Files.write(dir.resolve("environ"), block)
        environ(root, 31, "HOME=/home/me", "ANTHROPIC_API_KEY=never-read")
        environ(root, 32, "SPLICE=0", "ANTHROPIC_BASE_URL=http://127.0.0.1:3099")
        environ(root, 33)
        val unreadable = environ(root, 34, "HOME=/home/me")
        Files.setPosixFilePermissions(unreadable, emptySet())
        Files.createDirectories(root.resolve("proc").resolve("35").resolve("environ"))
        val env = environment(root)
        assertEquals(SessionRoute.Direct, env.route(21, heads), "a look-alike key is not the marker")
        assertEquals(SessionRoute.Direct, env.route(31, heads), "read, no SPLICE=1: talks to its provider directly")
        assertEquals(SessionRoute.Direct, env.route(32, heads), "SPLICE=0 is not SPLICE=1")
        assertEquals(SessionRoute.Unknown, env.route(33, heads), "an empty environment is no evidence of anything")
        assertEquals(SessionRoute.Unknown, env.route(34, heads), "another user's process: permission denied")
        assertEquals(SessionRoute.Unknown, env.route(35, heads), "a read that fails is not a read")
        assertEquals(SessionRoute.Unknown, env.route(99, heads), "no such process reads as no environment")
    }

    // Key rotation (Oct 26, 2026): a session launched before the key helper holds its turn key in
    // ANTHROPIC_AUTH_TOKEN, which a rotated key strands, so it is relaunched before the rotation. What tells it is
    // whether its environment holds that variable with a value; the value is a credential and is never kept.
    @Test
    fun `whether an environment holds a variable is answered by its name and a value, never what the value is`(
        @TempDir root: Path,
    ) {
        environ(root, 61, "HOME=/home/me", "ANTHROPIC_AUTH_TOKEN=a-turn-key", "SPLICE=1")
        environ(root, 62, "ANTHROPIC_AUTH_TOKEN_X=1", "X_ANTHROPIC_AUTH_TOKEN=1", "ANTHROPIC_AUTH=1")
        environ(root, 63, "HOME=/home/me", "ANTHROPIC_AUTH_TOKEN=")
        environ(root, 64)
        val env = environment(root)
        assertEquals(true, env.carries(61, TOKEN))
        assertEquals(false, env.carries(62, TOKEN), "a look-alike name is not the variable")
        assertEquals(false, env.carries(63, TOKEN), "an empty value holds no key: Claude Code reads it as unset")
        assertEquals(null, env.carries(64, TOKEN), "an empty environment was not read")
        assertEquals(null, env.carries(99, TOKEN), "no such process: nothing was read")
    }

    // The scan is a parser of whatever block the kernel hands back, so the answer is checked against a plain model
    // of it over generated blocks: entries split at NUL, a name ends at the first `=`, and a value must not be empty.
    @Test
    fun `the answer agrees with the plain reading of any environment block`(@TempDir root: Path) {
        val random = Random(SEED)
        val env = environment(root)
        repeat(GENERATED_BLOCKS) { n ->
            val pid = 1000L + n
            val entries = List(random.nextInt(MAX_ENTRIES)) { generatedEntry(random) }
            environ(root, pid, *entries.toTypedArray())
            val block = entries.joinToString("\u0000")
            val modelled = block.split('\u0000').any { entry ->
                entry.substringBefore('=', "") == TOKEN && entry.length > TOKEN.length + 1
            }
            val expected = if (block.isEmpty()) null else modelled
            assertEquals(expected, env.carries(pid, TOKEN)) { "block ${entries.map { it.take(40) }}" }
        }
    }

    /** One entry, as likely to be the variable or a near miss of it as anything else, with a value or none. */
    private fun generatedEntry(random: Random): String {
        val names = listOf(TOKEN, "${TOKEN}_X", "X$TOKEN", TOKEN.dropLast(1), "ANTHROPIC_AUTH", "HOME", "", "=")
        val name = names[random.nextInt(names.size)]
        val value = buildString { repeat(random.nextInt(4)) { append(VALUE_CHARS.random(random)) } }
        return if (random.nextBoolean()) "$name=$value" else name + value
    }
}

private const val TOKEN = "ANTHROPIC_AUTH_TOKEN"

// why: a fixed seed, so a failing block is the same block on the next run
private const val SEED = 20261026L
private const val GENERATED_BLOCKS = 400
private const val MAX_ENTRIES = 6

// why: an `=` inside a value, and a character UTF-8 spells in two bytes
private const val VALUE_CHARS = "ab=c\u00e9 "

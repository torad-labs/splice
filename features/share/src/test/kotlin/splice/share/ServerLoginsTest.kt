// What a laptop keeps of a team's server after `splice login <server>` (docs/specs/server-mode.md, F2), seen the way
// Claude Code sees it: the apiKeyHelper of a command on that server, run as its shell runs it, prints the key the last
// sign-in kept, a sign-in again hands out the new key, and `splice logout <server>` leaves it nothing to print.
package splice.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

class ServerLoginsTest {
    @TempDir lateinit var folder: Path

    private val logins by lazy { ServerLogins(folder) }
    private val server = "https://AI.team.example/"

    private fun key(letter: Char) = "spk_abcd1234_" + letter.toString().repeat(40)

    private fun signIn(key: String, device: String) =
        logins.signedIn(server, device, ServerSignedIn("dana@team.example", key, key.substring(0, 12), null))

    /** The helper run as Claude Code runs it: its exit code and what it printed on stdout and stderr. */
    private fun run(helper: String): Triple<Int, String, String> {
        val p = ProcessBuilder("sh", "-c", helper).start()
        p.waitFor(10, TimeUnit.SECONDS)
        return Triple(p.exitValue(), p.inputStream.reader().readText().trim(), p.errorStream.reader().readText())
    }

    @Test
    fun `the helper prints the key the last sign-in kept, and a sign-in again hands out the new one`() {
        val device = logins.device(server, "Dana-Laptop.local")
        signIn(key('a'), device)
        val helper = requireNotNull(logins.helper(server)) { "a server signed in to has no helper" }
        assertEquals(Triple(0, key('a'), ""), run(helper))
        assertFalse(key('a') in helper, "the helper holds the key itself, so a new sign-in could not reach it")

        signIn(key('b'), logins.device(server, "Dana-Laptop.local"))
        assertEquals(key('b'), run(helper).second)
        assertEquals(key('b'), logins.of("https://ai.team.example:443"), "one server written two ways is two")
    }

    @Test
    fun `the key file is owner-only and the record beside it never holds the key`() {
        signIn(key('a'), logins.device(server, "host"))
        val files = Files.list(folder).use { it.toList() }
        val keyFile = files.single { it.toString().endsWith(".key") }
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        assertEquals(ownerOnly, Files.getPosixFilePermissions(keyFile))
        files.filterNot { it == keyFile }.forEach { assertFalse(key('a') in Files.readString(it), "$it holds the key") }
    }

    @Test
    fun `logout leaves the helper nothing to print and says how to sign in, and keeps the device`() {
        val device = logins.device(server, "dana-laptop")
        assertTrue(device.startsWith("dana-laptop-"), device)
        signIn(key('a'), device)
        val helper = requireNotNull(logins.helper(server)) { "a server signed in to has no helper" }

        assertTrue(logins.forget(server))
        val (code, out, err) = run(helper)
        assertNotEquals(0, code)
        assertEquals("", out)
        assertTrue("splice login https://ai.team.example:443" in err, err)
        assertNull(logins.person(server))
        assertEquals(device, logins.device(server, "dana-laptop"), "signing in again named another device")
        assertFalse(logins.forget(server), "a second logout said it forgot a key")
    }

    @Test
    fun `a server this laptop never signed in to gets no helper, and a word that is no server is no origin`() {
        assertNull(logins.helper("http://devbox.lan:3097"))
        assertNull(logins.origin("devbox.lan"))
        assertNull(logins.origin("ftp://devbox.lan"))
    }
}

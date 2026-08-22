// NEW: KeyStore — the durable api-key store behind `splice key set` / `<head> login` /
// token capture. Round-trip, precedence-irrelevant mechanics (that lives in the provider test),
// parse tolerance, and the 0600 + SPLICE_CONFIG-sibling path contract.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KeyStoreTest {

    private fun store(tmp: Path) = KeyStore(tmp.resolve("keys.toml"))

    @Test
    fun `write then read round-trips and lists names only`(@TempDir tmp: Path) {
        val s = store(tmp)
        s.write("OPENROUTER_API_KEY", "sk-or-v1-abc123")
        s.write("MOONSHOT_API_KEY", "sk-moon-456")
        assertEquals("sk-or-v1-abc123", s.read("OPENROUTER_API_KEY"))
        assertEquals(setOf("OPENROUTER_API_KEY", "MOONSHOT_API_KEY"), s.names())
    }

    @Test
    fun `file is owner-only 0600 from creation`(@TempDir tmp: Path) {
        val s = store(tmp)
        s.write("OPENROUTER_API_KEY", "sk-or-v1-abc123")
        val perms = Files.getPosixFilePermissions(s.path)
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun `rewrite preserves siblings and last assignment wins`(@TempDir tmp: Path) {
        val s = store(tmp)
        s.write("A_KEY", "one")
        s.write("B_KEY", "two")
        s.write("A_KEY", "three")
        assertEquals("three", s.read("A_KEY"))
        assertEquals("two", s.read("B_KEY"))
    }

    @Test
    fun `tolerates comments blanks quotes and junk lines`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("keys.toml"),
            "# comment\n\nOPENROUTER_API_KEY = 'sk-or-single'\nMOONSHOT_API_KEY = \"sk-moon-double\"\njunk line\n",
        )
        val s = store(tmp)
        assertEquals("sk-or-single", s.read("OPENROUTER_API_KEY"))
        assertEquals("sk-moon-double", s.read("MOONSHOT_API_KEY"))
        assertEquals(2, s.names().size)
    }

    @Test
    fun `missing file reads as empty`(@TempDir tmp: Path) {
        val s = store(tmp)
        assertNull(s.read("OPENROUTER_API_KEY"))
        assertTrue(s.names().isEmpty())
    }

    @Test
    fun `unset removes only the named key`(@TempDir tmp: Path) {
        val s = store(tmp)
        s.write("A_KEY", "one")
        s.write("B_KEY", "two")
        assertTrue(s.unset("A_KEY"))
        assertNull(s.read("A_KEY"))
        assertEquals("two", s.read("B_KEY"))
        assertTrue(!s.unset("NEVER_SET"))
    }

    @Test
    fun `invalid names and empty values are rejected`(@TempDir tmp: Path) {
        val s = store(tmp)
        assertThrows(IllegalArgumentException::class.java) { s.write("lowercase", "x") }
        assertThrows(IllegalArgumentException::class.java) { s.write("HAS-DASH", "x") }
        assertThrows(IllegalArgumentException::class.java) { s.write("OK_NAME", "  ") }
    }

    @Test
    fun `defaultPath follows SPLICE_CONFIG sibling then XDG then home`() {
        val withOverride = KeyStorePath.defaultPath { k -> if (k == "SPLICE_CONFIG") "/tmp/rig/splice.toml" else null }
        assertEquals(Path.of("/tmp/rig/keys.toml"), withOverride)
        val withXdg = KeyStorePath.defaultPath { k -> if (k == "XDG_CONFIG_HOME") "/tmp/xdg" else null }
        assertEquals(Path.of("/tmp/xdg/splice/keys.toml"), withXdg)
        val withNothing = KeyStorePath.defaultPath { null }
        assertEquals(Path.of(System.getProperty("user.home"), ".config", "splice", "keys.toml"), withNothing)
    }

    @Test
    fun `unreadable store aborts the write and the file is byte-identical after - SH-11`() {
        val dir = Files.createTempDirectory("keys-unreadable")
        val path = dir.resolve("keys.toml")
        val store = KeyStore(path)
        store.write("OPENROUTER_API_KEY", "sk-a")
        store.write("FIREWORKS_API_KEY", "sk-b")
        val before = Files.readAllBytes(path)
        // strip read permission: entries() used to collapse this to "empty store" and the next
        // persist() rebuilt a ONE-key file, silently deleting every sibling key.
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("-wx------"))
        try {
            val thrown = try {
                store.write("MOONSHOT_API_KEY", "sk-c")
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertTrue(thrown != null, "an unreadable store must abort the write loudly")
            assertTrue(thrown!!.message!!.contains("refusing to write"), "got: " + thrown.message)
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        assertTrue(before.contentEquals(Files.readAllBytes(path)), "the store must be byte-identical")
        assertEquals(setOf("OPENROUTER_API_KEY", "FIREWORKS_API_KEY"), store.names())
    }

    @Test
    fun `two concurrent writers of different names both land - SH-11`() {
        val dir = Files.createTempDirectory("keys-concurrent")
        val path = dir.resolve("keys.toml")
        // two INSTANCES (distinct channels — the cross-process shape, same-JVM variant)
        val a = KeyStore(path)
        val b = KeyStore(path)
        val t1 = Thread { repeat(20) { i -> a.write("AAA_KEY", "a-$i") } }
        val t2 = Thread { repeat(20) { i -> b.write("BBB_KEY", "b-$i") } }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        assertEquals("a-19", a.read("AAA_KEY"), "no lost update on AAA")
        assertEquals("b-19", a.read("BBB_KEY"), "no lost update on BBB")
    }
}

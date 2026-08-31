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
import splice.core.util.LogSink
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
    fun `structural characters round-trip byte for byte`(@TempDir tmp: Path) {
        val s = store(tmp)
        val expected = mapOf(
            "HASH_KEY" to "abc#def",
            "QUOTE_KEY" to "\"quoted\"",
            "SLASH_KEY" to "path\\segment",
            "COMBINED_KEY" to "a#\"b\\c",
        )
        expected.forEach(s::write)

        val reopened = KeyStore(s.path)
        expected.forEach { (name, value) -> assertEquals(value, reopened.read(name), name) }
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

    // DR-40: the display-path read stayed tolerant (right) and SILENT (wrong) — an unreadable
    // keys.toml read as empty, so readKey said auth-missing and `splice key list` corroborated the
    // misdiagnosis while the keys sat intact one parse error away. Corrupt-vs-empty now differ by
    // ONE daemon-log line per file version (mtime-gated, or auth paths would firehose the log).
    @Test
    fun `an unreadable store logs corrupt-vs-empty once per file version`() {
        val dir = Files.createTempDirectory("keys-corrupt-log")
        val path = dir.resolve("keys.toml")
        val log = mutableListOf<String>()
        val store = KeyStore(path, log = LogSink { log += it })
        store.write("OPENROUTER_API_KEY", "sk-a")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("-wx------"))
        try {
            assertNull(store.read("OPENROUTER_API_KEY"), "unreadable still degrades to empty for display")
            assertNull(store.read("OPENROUTER_API_KEY"))
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(1, log.count { it.contains("UNREADABLE") }, "one warning per file version, got $log")
        assertEquals("sk-a", store.read("OPENROUTER_API_KEY"), "the keys were never lost")
    }

    // DR-40 redo 2 (codex race probe): the version latch was volatile check-then-set — the exact
    // DR-9 race; their 64-reader probe logged 29 warnings across 20 versions. CAS now: barriered
    // reader rounds, one distinct mtime each, must produce exactly one line per broken version.
    @Test
    fun `concurrent readers of an unreadable store warn exactly once per version - DR-40`() {
        val dir = Files.createTempDirectory("keys-concurrent-warn")
        val path = dir.resolve("keys.toml")
        val log = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val store = KeyStore(path, log = LogSink { log += it })
        store.write("OPENROUTER_API_KEY", "sk-a")
        val readers = 64
        val rounds = 8
        val pool = java.util.concurrent.Executors.newFixedThreadPool(readers)
        try {
            repeat(rounds) { round ->
                // Bump the version while readable (setLastModifiedTime opens the file), then break
                // it again — the reader rounds are barriered, so no read sees the readable window.
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                Files.setLastModifiedTime(
                    path,
                    java.nio.file.attribute.FileTime.fromMillis(1_000_000L + round * 10_000L),
                )
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("-wx------"))
                val start = java.util.concurrent.CountDownLatch(1)
                val done = java.util.concurrent.CountDownLatch(readers)
                repeat(readers) {
                    pool.execute {
                        start.await()
                        store.read("OPENROUTER_API_KEY")
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "readers wedged")
            }
        } finally {
            pool.shutdownNow()
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(rounds, log.count { it.contains("UNREADABLE") }, "one warning per version: $log")
    }

    // DR-40 redo (codex sentinel trap): an ACCESS-INDETERMINATE store has no readable mtime either,
    // and the old latch initialized to the same Long.MIN_VALUE the unreadable-mtime path produced —
    // so exactly these stores had their FIRST warning swallowed. Streak contract: first inaccessible
    // read warns once, repeats stay one, a healthy read re-arms, the next episode warns again.
    @Test
    fun `an inaccessible store warns on its FIRST episode and re-arms after recovery - DR-40`(@TempDir tmp: Path) {
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val log = mutableListOf<String>()
        val store = KeyStore(externalDir.resolve("keys.toml"), log = LogSink { log += it })
        store.write("OPENROUTER_API_KEY", "sk-a")
        val denied = PosixFilePermissions.fromString("---------")
        val open = PosixFilePermissions.fromString("rwx------")
        try {
            Files.setPosixFilePermissions(externalDir, denied)
            assertNull(store.read("OPENROUTER_API_KEY"))
            assertNull(store.read("OPENROUTER_API_KEY"))
            assertEquals(1, log.count { it.contains("UNREADABLE") }, "the FIRST episode must warn: $log")

            Files.setPosixFilePermissions(externalDir, open)
            assertEquals("sk-a", store.read("OPENROUTER_API_KEY"), "healthy read re-arms the latch")

            Files.setPosixFilePermissions(externalDir, denied)
            assertNull(store.read("OPENROUTER_API_KEY"))
        } finally {
            Files.setPosixFilePermissions(externalDir, open)
        }
        assertEquals(2, log.count { it.contains("UNREADABLE") }, "a new episode warns again: $log")
    }

    // DR-40 redo (class law): a DANGLING store symlink throws NoSuch on read, but the entry exists —
    // present-but-broken, not empty. A truly absent store stays the quiet empty.
    @Test
    fun `a dangling store symlink warns while true absence stays quiet - DR-40`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()
        val absent = KeyStore(tmp.resolve("keys.toml"), log = LogSink { log += it })
        assertTrue(absent.names().isEmpty())
        assertTrue(log.isEmpty(), "a genuinely absent store must not warn: $log")

        val link = tmp.resolve("linked.toml").also { Files.createSymbolicLink(it, tmp.resolve("gone.toml")) }
        val dangling = KeyStore(link, log = LogSink { log += it })
        assertTrue(dangling.names().isEmpty())
        assertEquals(1, log.count { it.contains("UNREADABLE") }, "a dangling store link must warn: $log")
    }

    // DR-40 redo (codex write repro): the operator's keys.toml is a symlink whose target parent lost
    // read. The old exists() pre-gate read that as "no store", so a write rebuilt a ONE-key file and
    // atomically REPLACED the symlink — every sibling key dropped. The write must abort; the link
    // and the real store survive untouched.
    @Test
    fun `a write never replaces an inaccessible store symlink - DR-40`(@TempDir tmp: Path) {
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val target = externalDir.resolve("keys.toml")
        KeyStore(target).write("OPENROUTER_API_KEY", "sk-a")
        val before = Files.readAllBytes(target)
        val link = tmp.resolve("keys.toml").also { Files.createSymbolicLink(it, target) }
        val store = KeyStore(link)
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        try {
            val thrown = runCatching { store.write("MOONSHOT_API_KEY", "sk-c") }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException, "an inaccessible store must abort the write: $thrown")
            assertTrue(Files.isSymbolicLink(link), "the operator's store symlink must survive")
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(before.contentEquals(Files.readAllBytes(target)), "the real store keeps every key")
        assertEquals(setOf("OPENROUTER_API_KEY"), store.names())
    }

    // DR-40 redo: a dangling store symlink on the WRITE path — the entry exists, so seeding a fresh
    // one-key file would replace the operator's (repairable) link. Refuse instead.
    @Test
    fun `a write refuses to seed over a dangling store symlink - DR-40`(@TempDir tmp: Path) {
        val link = tmp.resolve("keys.toml").also { Files.createSymbolicLink(it, tmp.resolve("gone.toml")) }
        val store = KeyStore(link)
        val thrown = runCatching { store.write("MOONSHOT_API_KEY", "sk-c") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "a dangling store link must abort the write: $thrown")
        assertTrue(Files.isSymbolicLink(link), "the dangling link must not be replaced")
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

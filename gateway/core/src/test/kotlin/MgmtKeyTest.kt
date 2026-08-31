// NEW (SH-12): MgmtKey mint-path pins — the key was previously exercised only indirectly via
// bearer matching. Absent file = quiet first-run mint; a PRESENT-but-unreadable/blank file mints
// LOUDLY (the silent fallthrough revoked every bearer with no explanation) and records mintedAtMs.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class MgmtKeyTest {

    private fun paths(tmp: Path) = StatePaths(baseOverride = tmp.resolve("state"))

    @Test
    fun `absent file mints quietly - the first-run path`(@TempDir tmp: Path) {
        val logs = mutableListOf<String>()
        val key = MgmtKey(paths(tmp), log = logs::add, clock = { 42L })
        val minted = key.get()
        assertEquals(64, minted.length, "32 random bytes hex")
        assertTrue(logs.isEmpty(), "a first-run mint is expected, not warned about: $logs")
        assertEquals(42L, key.mintedAtMs)
    }

    @Test
    fun `existing readable key is served, never re-minted`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        Files.createDirectories(sp.mgmtKeyFile.parent)
        Files.writeString(sp.mgmtKeyFile, "abc123\n")
        val logs = mutableListOf<String>()
        val key = MgmtKey(sp, log = logs::add)
        assertEquals("abc123", key.get())
        assertTrue(logs.isEmpty())
        assertEquals(null, key.mintedAtMs, "serving an existing key is not a mint")
    }

    @Test
    fun `unreadable existing file mints LOUDLY and records the mint - SH-12`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        Files.createDirectories(sp.mgmtKeyFile.parent)
        Files.writeString(sp.mgmtKeyFile, "old-key\n")
        Files.setPosixFilePermissions(sp.mgmtKeyFile, PosixFilePermissions.fromString("-wx------"))
        val logs = mutableListOf<String>()
        try {
            val key = MgmtKey(sp, log = logs::add, clock = { 7L })
            val minted = key.get()
            assertNotEquals("old-key", minted)
            assertEquals(1, logs.size, "exactly one loud line: $logs")
            assertTrue(logs[0].contains(sp.mgmtKeyFile.toString()), "names the path: ${logs[0]}")
            assertTrue(logs[0].contains("every existing bearer"), "names the consequence: ${logs[0]}")
            assertEquals(7L, key.mintedAtMs)
        } finally {
            Files.setPosixFilePermissions(sp.mgmtKeyFile, PosixFilePermissions.fromString("rw-------"))
        }
    }

    // DR-56: an access-INDETERMINATE key is not a first run. The operator's key sits behind a symlink
    // whose target parent loses read (a permissions blip); any exists() pre-gate reads false there, so
    // ensure() used to skip the read block and mint QUIETLY — SH-12 demands a LOUD rotation. The
    // direct read reaches the AccessDenied and classifies it; NOFOLLOW exists only as the post-NoSuch
    // dangling disambiguator, never a gate.
    @Test
    fun `an inaccessible-target key symlink rotates LOUDLY, not a silent first run - SH-12 DR-56`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        Files.createDirectories(sp.mgmtKeyFile.parent)
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val target = Files.writeString(externalDir.resolve("mgmt.key"), "old-key\n")
        Files.createSymbolicLink(sp.mgmtKeyFile, target)
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        val logs = mutableListOf<String>()
        try {
            val key = MgmtKey(sp, log = logs::add, clock = { 9L })
            val minted = key.get()
            assertEquals(64, minted.length)
            assertNotEquals("old-key", minted)
            assertEquals(1, logs.size, "an inaccessible key symlink must rotate loudly, not mint quietly: $logs")
            assertTrue(logs[0].contains("every existing bearer"), "names the consequence: ${logs[0]}")
            assertEquals(9L, key.mintedAtMs)
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // DR-56 (codex class law): the key file sits DIRECTLY under a dir whose search bit is gone — no
    // symlink. Files.exists(path, NOFOLLOW) still reads false here (it can't stat through an
    // untraversable parent), so a NOFOLLOW pre-gate mints SILENTLY. A direct read hits AccessDenied
    // and classifies it LOUDLY before the inevitable mint-into-the-same-dir failure propagates.
    @Test
    fun `an inaccessible state dir warns LOUDLY before the mint fails - SH-12 DR-56`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        Files.createDirectories(sp.mgmtKeyFile.parent)
        Files.writeString(sp.mgmtKeyFile, "old-key\n")
        Files.setPosixFilePermissions(sp.mgmtKeyFile.parent, PosixFilePermissions.fromString("---------"))
        val logs = mutableListOf<String>()
        try {
            val outcome = runCatching { MgmtKey(sp, log = logs::add, clock = { 11L }).get() }
            // The mint into the same untraversable dir CANNOT succeed — a mutant that warns and then
            // reports a key as if minted must not survive (codex).
            assertTrue(outcome.isFailure, "the mint into an untraversable dir must fail: $outcome")
            assertEquals(1, logs.size, "an inaccessible state dir must warn before the mint fails: $logs")
            assertTrue(logs[0].contains("every existing bearer"), "names the consequence: ${logs[0]}")
        } finally {
            Files.setPosixFilePermissions(sp.mgmtKeyFile.parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // DR-56 (codex class law): a DANGLING key symlink throws NoSuchFile on read, but the path entry
    // exists — it is NOT a first run. exists(NOFOLLOW) disambiguates: present link => rotate loudly,
    // not a quiet mint that would silently swap the operator's (repairable) symlink for a fresh key.
    @Test
    fun `a dangling key symlink rotates LOUDLY, not a silent first run - SH-12 DR-56`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        Files.createDirectories(sp.mgmtKeyFile.parent)
        Files.createSymbolicLink(sp.mgmtKeyFile, tmp.resolve("never-created.key"))
        val logs = mutableListOf<String>()
        val key = MgmtKey(sp, log = logs::add, clock = { 13L })
        val minted = key.get()
        assertEquals(64, minted.length)
        assertEquals(1, logs.size, "a dangling key symlink is not a first run — the entry exists: $logs")
        assertTrue(logs[0].contains("dangling symlink"), "classifies the dangling link: ${logs[0]}")
        assertEquals(13L, key.mintedAtMs)
    }

    @Test
    fun `blank existing file is unreadable state, not a valid key - SH-12`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        Files.createDirectories(sp.mgmtKeyFile.parent)
        Files.writeString(sp.mgmtKeyFile, "   \n")
        val logs = mutableListOf<String>()
        val key = MgmtKey(sp, log = logs::add)
        assertEquals(64, key.get().length)
        assertEquals(1, logs.size, "$logs")
        assertTrue(logs[0].contains("present but blank"), logs[0])
    }
}

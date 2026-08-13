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

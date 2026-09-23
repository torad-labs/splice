// NEW: v0.4.0 key split — the turn key's own contract. It shares MgmtKey's mint semantics through
// StateKey (MgmtKeyTest pins those), so these arms pin only what is NEW: it is a different secret in a
// different file, and the header file the statusline command names always carries the CURRENT key.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class TurnKeyTest {

    private fun paths(tmp: Path) = StatePaths(baseOverride = tmp.resolve("state"))

    @Test
    fun `the turn key is its own secret, never the management key`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        val turn = TurnKey(sp, log = {})
        val mgmt = MgmtKey(sp, log = {})
        assertEquals(64, turn.get().length, "32 random bytes hex")
        assertNotEquals(mgmt.get(), turn.get(), "the split is the security property")
        assertFalse(turn.matchesBearer("Bearer ${mgmt.get()}"), "the management key is not a turn key")
        assertTrue(turn.matchesBearer("Bearer ${turn.get()}"))
    }

    @Test
    fun `the header file is owner-only and carries the current key as one header line`(@TempDir tmp: Path) {
        val turn = TurnKey(paths(tmp), log = {})
        val file = turn.headerFile()
        assertEquals("Authorization: Bearer ${turn.get()}\n", Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a stale header file is rewritten to the current key, not trusted`(@TempDir tmp: Path) {
        // A key rotated while the header file kept the old bearer would 401 every statusline tick
        // with nothing on disk saying why. The file is re-derived from the key at every use.
        val sp = paths(tmp)
        val turn = TurnKey(sp, log = {})
        val file = turn.headerFile()
        Files.writeString(file, "Authorization: Bearer stale-key\n")
        turn.headerFile()
        assertEquals("Authorization: Bearer ${turn.get()}\n", Files.readString(file))
    }

    @Test
    fun `a second daemon instance serves the same turn key - minted once per key lifetime`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        val first = TurnKey(sp, log = {}).get()
        val logs = mutableListOf<String>()
        val second = TurnKey(sp, log = logs::add)
        assertEquals(first, second.get(), "a restart must not strand every launched session")
        assertEquals(null, second.mintedAtMs)
        assertTrue(logs.isEmpty(), logs.toString())
    }
}

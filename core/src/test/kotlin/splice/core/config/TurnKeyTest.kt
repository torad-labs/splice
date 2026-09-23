// NEW: v0.4.0 key split — the turn key's own contract. It is DERIVED from the management key
// (MgmtKeyTest pins the mint), so these arms pin what the derivation must hold: a different secret,
// stable across daemon restarts, rotated with the management key, and a header file that always
// carries the CURRENT key.
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
        val mgmt = MgmtKey(paths(tmp), log = {})
        val turn = TurnKey(mgmt)
        assertEquals(64, turn.get().length, "HMAC-SHA256 hex")
        assertNotEquals(mgmt.get(), turn.get(), "the split is the security property")
        assertFalse(turn.matchesBearer("Bearer ${mgmt.get()}"), "the management key is not a turn key")
        assertFalse(mgmt.matchesBearer("Bearer ${turn.get()}"), "the turn key is not the management key")
        assertTrue(turn.matchesBearer("Bearer ${turn.get()}"))
    }

    @Test
    fun `a restarted daemon derives the same turn key - launched sessions survive it`(@TempDir tmp: Path) {
        val first = TurnKey(MgmtKey(paths(tmp), log = {})).get()
        assertEquals(first, TurnKey(MgmtKey(paths(tmp), log = {})).get())
    }

    @Test
    fun `rotating the management key rotates the turn key with it`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        val before = TurnKey(MgmtKey(sp, log = {})).get()
        Files.delete(sp.mgmtKeyFile)
        assertNotEquals(before, TurnKey(MgmtKey(sp, log = {})).get())
    }

    @Test
    fun `the header file is owner-only, beside the management key, one header line`(@TempDir tmp: Path) {
        val sp = paths(tmp)
        val turn = TurnKey(MgmtKey(sp, log = {}))
        val file = turn.headerFile()
        assertEquals(sp.mgmtKeyFile.parent, file.parent)
        assertEquals("Authorization: Bearer ${turn.get()}\n", Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a stale header file is rewritten to the current key, not trusted`(@TempDir tmp: Path) {
        val turn = TurnKey(MgmtKey(paths(tmp), log = {}))
        val file = turn.headerFile()
        Files.writeString(file, "Authorization: Bearer stale-key\n")
        turn.headerFile()
        assertEquals("Authorization: Bearer ${turn.get()}\n", Files.readString(file))
    }
}

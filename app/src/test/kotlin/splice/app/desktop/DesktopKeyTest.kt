// desktop-door, decision 6: the desktop key a person set in Claude Desktop is only replaced in silence on a true first
// run. A key file that is there but blank, or a symlink to nothing, is said in the daemon's log before a new key is
// made, since that new key refuses the one Desktop holds.
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.desktop.DESKTOP_KEY_PREFIX
import java.nio.file.Files
import java.nio.file.Path

class DesktopKeyTest {

    @Test
    fun `a first run makes the key quietly, and the next start serves the same one`(@TempDir tmp: Path) {
        val file = tmp.resolve("desktop-key")
        val logs = mutableListOf<String>()
        val key = DesktopKey(file, logs::add).get()
        assertTrue(key.startsWith(DESKTOP_KEY_PREFIX), key)
        assertEquals(DESKTOP_KEY_PREFIX.length + 64, key.length, "32 random bytes as hex")
        assertTrue(logs.isEmpty(), "$logs")
        assertEquals(key, DesktopKey(file, logs::add).get())
    }

    @Test
    fun `a blank key file is said in the log before a new key is made`(@TempDir tmp: Path) {
        val file = Files.writeString(tmp.resolve("desktop-key"), "")
        val logs = mutableListOf<String>()
        val key = DesktopKey(file, logs::add).get()
        assertEquals(1, logs.size, "$logs")
        assertTrue(logs[0].contains("$file present but blank"), logs[0])
        assertTrue(logs[0].contains("the desktop key Claude Desktop held"), "names the key it refuses: ${logs[0]}")
        assertFalse(logs[0].contains(key), "the key is never written to the log: ${logs[0]}")
    }

    @Test
    fun `a dangling key symlink is said in the log, not taken for a first run`(@TempDir tmp: Path) {
        val file = Files.createSymbolicLink(tmp.resolve("desktop-key"), tmp.resolve("gone"))
        val logs = mutableListOf<String>()
        DesktopKey(file, logs::add).get()
        assertEquals(1, logs.size, "$logs")
        assertTrue(logs[0].contains("dangling symlink"), logs[0])
    }

    @Test
    fun `a replaced key is refused and the new one opens`(@TempDir tmp: Path) {
        val key = DesktopKey(tmp.resolve("desktop-key")) {}
        val old = key.get()
        val new = key.replace()
        assertFalse(key.matchesBearer("Bearer $old"))
        assertTrue(key.matchesBearer("Bearer $new"))
        assertEquals(new, DesktopKey(tmp.resolve("desktop-key")) {}.get(), "the next start serves the new key")
    }
}

// NEW: v0.4.0 review — the daemon's first act on its state is to hold what splice owns at 0700
// (DaemonProcess.secureStateDirs, called before the lock is written). It held state/ and left the
// ROOT above it at the umask's 775, where the compact-stats files sit at 664; and nothing pinned the
// step at all, so a start that skipped it would have passed every test.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DaemonStateDirsTest {

    private val process = DaemonProcess()

    private fun mode(dir: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))

    private fun openDir(dir: Path): Path {
        val created = Files.createDirectories(dir)
        Files.setPosixFilePermissions(created, PosixFilePermissions.fromString("rwxrwxr-x"))
        return created
    }

    @Test
    fun `an open root splice chose and its state dir are tightened on start`(@TempDir home: Path) {
        val root = openDir(home.resolve(".splice"))
        val state = openDir(root.resolve("state"))
        Files.writeString(root.resolve("claudex-compact-stats.jsonl"), "{}\n")

        val open = process.secureStateDirs(StatePaths(envReader = EnvReader { null }, homeDir = home))

        assertEquals(emptyList<String>(), open)
        assertEquals("rwx------", mode(root), "the root holds the compact-stats files")
        assertEquals("rwx------", mode(state))
    }

    @Test
    fun `the parent of a state dir the operator named is left as it was`(@TempDir tmp: Path) {
        val parent = openDir(tmp.resolve("operator-owned"))
        val state = parent.resolve("state")

        process.secureStateDirs(StatePaths(baseOverride = state))

        assertEquals("rwxrwxr-x", mode(parent))
        assertEquals("rwx------", mode(state))
    }
}

// NEW: v0.4.0 review — the daemon's first act on its state is to hold what splice owns at 0700
// (DaemonProcess.secureStateDirs, called before the lock is written). It held state/ and left the
// ROOT above it at the umask's 775, where the compact-stats files sit at 664; and nothing pinned the
// step at all, so a start that skipped it would have passed every test. V4-280: the start's own
// steps up to the lock are DaemonProcess.prepare, which runDaemon runs and the first test drives.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.net.URI
import java.nio.file.FileSystems
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

    /** V4-280: the start itself, not the two steps called one by one. Without secureStateDirs in it the
     *  state dir stays open; without secureConfig, splice.toml does. */
    @Test
    fun `the start holds an open state dir and an open splice toml owner-only before its lock - V4-280`(
        @TempDir tmp: Path,
    ) {
        val state = tmp.resolve("state")
        Files.setPosixFilePermissions(Files.createDirectories(state), PosixFilePermissions.fromString("rwxr-xr-x"))
        val config = tmp.resolve("config/splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "[daemon]\nstate_dir = \"$state\"\n")
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"))

        val start = process.prepare(EnvReader { if (it == "SPLICE_CONFIG") config.toString() else null })

        assertEquals(config, start.topologyPath)
        assertEquals(state.resolve("daemon.lock"), start.statePaths.daemonLockFile, "the lock it takes next")
        assertEquals("rwx------", mode(state))
        assertEquals("rw-------", mode(config))
        assertTrue(start.ownerOnlyLines.any { "rw-r--r--" in it }, "the log names splice.toml's old mode")
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

    private fun openFile(file: Path): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, "[providers.ex.extra_headers]\nx-api-key = \"k\"\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        return file
    }

    // V4-278: V4-275 writes splice.toml and its backups 0600 from now on, but every install has ones an
    // older splice left at the umask, and they hold header secrets. Every start holds them owner-only.
    @Test
    fun `an open splice toml and its backups are owner-only after the start, the log naming the old mode - V4-278`(
        @TempDir home: Path,
    ) {
        val config = openFile(home.resolve(".config/splice/splice.toml"))
        val backup = openFile(home.resolve(".config/splice/splice.toml.bak-20260918T120000Z-0123456789ab"))
        val other = openFile(home.resolve(".config/splice/notes.txt"))

        val lines = process.secureConfig(config)

        assertEquals("rw-------", mode(config))
        assertEquals("rw-------", mode(backup))
        assertEquals("rw-r--r--", mode(other), "a file that is not splice.toml or its backup is left alone")
        assertEquals(2, lines.size, "one line per file whose mode changed: $lines")
        assertTrue(lines.all { "rw-r--r--" in it }, "each line names the mode the file had: $lines")
        assertEquals(emptyList<String>(), process.secureConfig(config), "a second start has nothing to say")
    }

    @Test
    fun `a symlinked splice toml tightens its target - V4-278`(@TempDir home: Path) {
        val target = openFile(home.resolve("dotfiles/splice.toml"))
        val link = home.resolve(".config/splice/splice.toml")
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, target)

        val _ = process.secureConfig(link)

        assertEquals("rw-------", mode(target))
        assertTrue(Files.isSymbolicLink(link), "the operator's link stays a link")
    }

    @Test
    fun `a splice toml the daemon cannot tighten leaves one warning, and the start goes on - V4-278`(
        @TempDir tmp: Path,
    ) {
        // zipfs without enablePosixFileAttributes keeps no POSIX modes: the mode cannot be held there.
        val zip = URI.create("jar:${tmp.resolve("config.zip").toUri()}")
        FileSystems.newFileSystem(zip, mapOf("create" to "true")).use { fs ->
            val config = fs.getPath("/splice.toml")
            Files.writeString(config, "[daemon]\n")

            val lines = process.secureConfig(config)

            assertEquals(1, lines.size, "one warning: $lines")
            assertTrue("WARNING" in lines.single() && "splice.toml" in lines.single(), lines.single())
        }
    }
}

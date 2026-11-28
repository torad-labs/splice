package splice.lifecycle.seatd

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Look for a running executable, not an open launcher script or the current pin. */
internal class SeatdActivity(private val platform: SeatdPlatform, private val root: Path) {
    private val linuxExecutables: List<Path> by lazy {
        Files.list(Path.of("/proc")).use { entries ->
            entries.filter { it.fileName.toString().toLongOrNull() != null }
                .map { executable(it) }.filter { it != null }.map { requireNotNull(it) }
                .filter { it.startsWith(root) }.toList()
        }
    }

    fun held(version: Path): Boolean {
        val dir = root.resolve(version)
        return if (platform.linux) linuxExecutables.any { it.startsWith(dir) } else macHeld(dir)
    }

    private fun executable(process: Path): Path? = try {
        Files.readSymbolicLink(process.resolve("exe"))
    } catch (_: IOException) {
        // Processes may exit during the scan or belong to another user.
        null
    }

    private fun macHeld(dir: Path): Boolean = try {
        // An empty, quiet exit 1 means there were no open text mappings in this directory.
        val probe = ProcessBuilder("/usr/sbin/lsof", "-nP", "-a", "-d", "txt", "-t", "+D", dir.toString())
            .redirectErrorStream(true).start()
        val output = probe.inputStream.bufferedReader().use { it.readText() }
        probe.waitFor() != 1 || output.isNotBlank()
    } catch (_: IOException) {
        // If lsof cannot look, leave the version for a later upgrade.
        true
    }
}

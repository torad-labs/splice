package splice.lifecycle.seatd

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: local text mappings answer quickly; a stuck mount must not hold the install lock indefinitely.
private const val SEATD_LSOF_SECONDS = 5L

internal enum class SeatdUse { IN_USE, UNUSED, UNKNOWN }

/** Look for a running executable, not an open launcher script or the current pin. */
internal class SeatdActivity(private val platform: SeatdPlatform, private val root: Path) {
    private val linuxExecutables: List<LinuxExecutable> by lazy {
        Files.list(Path.of("/proc")).use { entries ->
            entries.iterator().asSequence()
                .filter { it.fileName.toString().toLongOrNull() != null }
                .mapNotNull(::executable)
                .toList()
        }
    }

    fun usage(version: Path): SeatdUse {
        val dir = root.resolve(version)
        return if (platform.linux) linuxUse(dir) else macUse(dir)
    }

    private fun linuxUse(dir: Path): SeatdUse = when {
        linuxExecutables.any { it.path?.startsWith(dir) == true } -> SeatdUse.IN_USE
        linuxExecutables.any { it.path == null } -> SeatdUse.UNKNOWN
        else -> SeatdUse.UNUSED
    }

    private fun executable(process: Path): LinuxExecutable? = try {
        Files.readSymbolicLink(process.resolve("exe")).takeIf { it.startsWith(root) }?.let(::LinuxExecutable)
    } catch (_: NoSuchFileException) {
        // The process exited, or has no user executable.
        null
    } catch (_: IOException) {
        // Linux can hide exe even for our own process (for example, if it is not dumpable). An identifiable
        // companion without a visible executable path could be using any of the older directories.
        if (namesCompanion(process)) LinuxExecutable(null) else null
    }

    private fun namesCompanion(process: Path): Boolean = try {
        Files.readString(process.resolve("comm")).trimEnd() == SEATD_PIPE_BINARY
    } catch (_: IOException) {
        false
    }

    private fun macUse(dir: Path): SeatdUse = try {
        val probe = ProcessBuilder("/usr/sbin/lsof", "-nP", "-a", "-d", "txt", "-t", "+D", dir.toString())
            .redirectErrorStream(true).start()
        try {
            macResult(probe)
        } finally {
            if (probe.isAlive) probe.destroyForcibly()
        }
    } catch (_: IOException) {
        SeatdUse.UNKNOWN
    }

    private fun macResult(probe: Process): SeatdUse = probe.inputStream.use { output ->
        when {
            !probe.waitFor(SEATD_LSOF_SECONDS, TimeUnit.SECONDS) -> SeatdUse.UNKNOWN
            probe.exitValue() == 0 -> SeatdUse.IN_USE
            probe.exitValue() == 1 && output.read() == -1 -> SeatdUse.UNUSED
            else -> SeatdUse.UNKNOWN
        }
    }

    /** Null means the process is identifiable as a companion, but its executable location is not visible. */
    private data class LinuxExecutable(val path: Path?)
}

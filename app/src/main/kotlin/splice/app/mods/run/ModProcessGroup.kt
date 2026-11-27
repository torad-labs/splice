package splice.app.mods.run

import kotlinx.coroutines.future.await
import java.nio.file.Files
import java.nio.file.Path

/** Start the host in a fresh session so reparented children still share its process group. */
internal object ModProcessGroup {
    private val launcher = when {
        Files.isExecutable(Path.of("/usr/bin/setsid")) -> listOf("/usr/bin/setsid")
        Files.isExecutable(Path.of("/bin/setsid")) -> listOf("/bin/setsid")
        else -> listOf(
            "/usr/bin/perl",
            "-MPOSIX",
            "-e",
            "defined(POSIX::setsid()) or die $!; exec @ARGV or die $!",
            "--",
        )
    }

    fun isolate(command: ProcessBuilder): ProcessBuilder = command.command(launcher + command.command())

    suspend fun signal(pid: Long, force: Boolean): Boolean {
        val signal = if (force) "KILL" else "TERM"
        val command = ProcessBuilder("/bin/kill", "-s", signal, "--", "-$pid")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return command.onExit().await().exitValue() == 0
    }
}

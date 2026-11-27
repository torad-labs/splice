package splice.app.mods.run

import java.nio.file.Path

// why: give Bun time to finish its stop hook on EOF when its daemon was killed.
private const val MOD_PARENT_EXIT_GRACE_SECONDS = 4

/** Start the session guardian. Bun cannot run until the daemon publishes the guardian's identity. */
internal object ModProcessGroup {
    fun isolate(command: ProcessBuilder, guard: Path): ProcessBuilder =
        command.command(
            listOf(
                "/usr/bin/perl",
                guard.toString(),
                ProcessHandle.current().pid().toString(),
                MOD_PARENT_EXIT_GRACE_SECONDS.toString(),
            ) + command.command(),
        )

    fun admit(process: Process) {
        process.outputStream.write(0)
        process.outputStream.flush()
    }
}

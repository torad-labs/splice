package splice.app.mods.run

import java.nio.file.Path

/** The same Bun runs both scripts. Only the guardian receives the admission byte. */
internal object ModProcessGroup {
    fun isolate(command: ProcessBuilder, guard: Path): ProcessBuilder {
        val host = command.command().toList()
        return command.command(host.dropLast(1) + guard.toString() + host)
    }

    fun admit(process: Process) {
        process.outputStream.write(0)
        process.outputStream.flush()
    }
}

package splice.observability.profile

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Fixed argument vectors only. A timer kills the controller even when attach or output reading hangs. */
internal class NativeProfileCommand(private val executable: Path, private val supervisor: ScheduledExecutorService) {
    @Volatile private var process: Process? = null

    fun run(arguments: List<String>, destination: Path? = null) {
        val command = listOf(executable.toString()) + arguments + ProcessHandle.current().pid().toString()
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
        if (destination == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val child = builder.start()
        process = child
        val expiry = supervisor.schedule({ terminate(child) }, ProfileLimits.CONTROL_SECONDS, TimeUnit.SECONDS)
        try {
            if (destination != null) ProfileCopy.copy(child.inputStream, destination)
            if (!child.waitFor(ProfileLimits.CONTROL_SECONDS, TimeUnit.SECONDS)) throw ProfileTimedOut()
            if (child.exitValue() != 0) throw ProfileUnavailable()
        } finally {
            expiry.cancel(false)
            terminate(child)
            child.inputStream.close()
            child.outputStream.close()
            child.errorStream.close()
            process = null
        }
    }

    fun cancel() { process?.let(::terminate) }

    private fun terminate(child: Process) {
        child.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        child.destroyForcibly()
    }
}

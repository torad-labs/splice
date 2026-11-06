package splice.observability.profile

import splice.core.perf.runtime.PerfProfileRequest
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService

/** An explicitly configured asprof; no downloads, shell, sudo, or changes to kernel permissions. */
internal class NativeCapture(
    private val request: PerfProfileRequest,
    executable: Path,
    supervisor: ScheduledExecutorService,
) : ProfileCapture {
    private val command = NativeProfileCommand(executable, supervisor)
    private var started = false

    override fun start() {
        command.run(listOf("check", "-e", request.mode))
        // Set an in-agent timeout too: killing an attach controller alone does not stop its agent.
        command.run(
            listOf("start", "-e", request.mode, "--timeout", request.durationSeconds.toString(), "--memlimit", "16m"),
        )
        started = true
    }

    override fun finish(destination: Path) {
        command.run(listOf("stop", "-o", "collapsed"), destination)
        started = false
    }

    override fun close() {
        val interrupted = Thread.interrupted()
        try {
            stopOwned()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun stopOwned() {
        command.cancel()
        if (started) {
            try {
                command.run(listOf("stop"))
            } finally {
                started = false
            }
        }
    }
}

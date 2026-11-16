package splice.codemode.nativehost

import splice.codemode.nativehost.grpc.NativeGrpcErrors
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink

/** Operator-visible session loss retains process and typed failure facts, never guest source or exception messages. */
internal class NativeSessionDiagnostics(private val log: LogSink = LogSink(DaemonLog::write)) {
    fun lost(connection: String, process: Process, error: Throwable, session: String? = null) {
        val reported = Cancellables.runCatchingBestEffort {
            val pid = Cancellables.runCatchingBestEffort { process.pid() }.getOrNull()
            val exit = Cancellables.runCatchingBestEffort { process.exitValue() }.getOrNull()
            val alive = Cancellables.runCatchingBestEffort { process.isAlive }.getOrNull()
            val owner = session?.let { " session=$it" }.orEmpty()
            log(
                "[code-mode] native session lost connection=$connection$owner " +
                    "host_pid=${pid ?: "unknown"} host_alive=${alive ?: "unknown"} " +
                    "host_exit=${exit ?: "unknown"} causes=${causes(error)}",
            )
        }
        Cancellables.discard(reported, "Diagnostic sink failures must not prevent native-session cleanup")
    }

    fun replaced(previous: NativeSessionBinding, current: NativeSessionBinding) {
        val reported = Cancellables.runCatchingBestEffort {
            log(
                "[code-mode] native runtime replaced " +
                    "previous_connection=${previous.host.generation} previous_generation=${previous.generation} " +
                    "current_connection=${current.host.generation} current_generation=${current.generation}; " +
                    "stored values and running cells were lost; accepted source was not replayed",
            )
        }
        Cancellables.discard(reported, "A failed diagnostic sink cannot change the already-lost generation")
    }

    private fun causes(error: Throwable): String = generateSequence(error) { it.cause }
        .take(MAX_NATIVE_FAILURE_CAUSES)
        .joinToString(" <- ", transform = NativeGrpcErrors::classification)
}

// A corrupt cyclic cause chain cannot make a failure callback run or allocate without a bound.
private const val MAX_NATIVE_FAILURE_CAUSES: Int = 8

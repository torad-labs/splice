package splice.control.mcp.hosted

import splice.core.util.Cancellables
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// why: one minute between idle sweeps bounds maintenance churn; active requests and streams retain their leases.
private const val SWEEP_PERIOD_S = 60L

/** The host's periodic idle maintenance, distinct from request handling or stopping the host. */
internal fun interface McpIdleSweep {
    fun run()
}

/** Owns the host's scheduled idle-maintenance task and stops it before hosted processes are closed. */
internal class McpSweeper(private val sweep: McpIdleSweep) {
    @Volatile private var executor: ScheduledExecutorService? = null

    fun start() {
        val exec = Executors.newSingleThreadScheduledExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "mcp-host-sweep"
                isDaemon = true
            }
        }
        exec.scheduleAtFixedRate(
            { Cancellables.runCatchingCancellable { sweep.run() } },
            SWEEP_PERIOD_S,
            SWEEP_PERIOD_S,
            TimeUnit.SECONDS,
        )
        executor = exec
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }
}

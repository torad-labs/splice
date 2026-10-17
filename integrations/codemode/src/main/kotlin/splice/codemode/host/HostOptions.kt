// NEW: how the code-mode host pool is sized and how one host process is started (default-parameter width flip,
// 2026-10-09). JvmCodeModeRuntime took these as eleven constructor parameters that only made sense in two groups;
// each group is a type that validates itself and builds the part of the runtime it configures.
package splice.codemode.host

import splice.codemode.CodeModeHeap
import splice.codemode.DEFAULT_HEAP_MB
import splice.codemode.DEFAULT_MAX_WORKERS
import splice.codemode.DEFAULT_POOL_MEMORY_MB
import splice.codemode.DEFAULT_WORKER_START_TIMEOUT_MS
import splice.codemode.WorkerArtifacts
import splice.codemode.WorkerSpawn
import java.nio.file.Path

/** How the pool's hosts are admitted: how many may run, the memory the pool may reserve, and how long a session
 *  idles. */
public data class PoolLimits(
    public val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    public val memoryBudgetMb: Long = DEFAULT_POOL_MEMORY_MB,
    public val idleTimeoutMs: Long = CodeModeHeap.idleTimeoutMs,
) {
    init {
        require(maxWorkers > 0) { "Code-mode host count must be positive" }
        require(memoryBudgetMb > 0) { "Code-mode pool memory budget must be positive" }
        require(idleTimeoutMs > 0) { "Code-mode session idle timeout must be positive" }
    }

    internal fun admission(heapMb: Int): CodeModePoolAdmission =
        CodeModePoolAdmission(maxWorkers, heapMb, memoryBudgetMb)

    internal fun times(controlMs: Long): CodeModePoolTimes = CodeModePoolTimes(idleTimeoutMs, controlMs)
}

/** How one host process is started: its heap, the JVM and classpath it runs, the OS boundary that spawns it, and how
 *  long it has to come up. */
public data class HostLaunch(
    public val heapMb: Int = DEFAULT_HEAP_MB,
    public val classpath: String = WorkerArtifacts.runningClasspath(),
    public val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    public val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    public val startTimeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
) {
    init {
        require(startTimeoutMs > 0) { "Code-mode worker start timeout must be positive" }
        require(heapMb > 0) { "Code-mode host heap must be positive" }
        require(classpath.isNotBlank()) { "Code-mode worker classpath is required" }
    }
}

package splice.codemode

import splice.codemode.host.HostLaunch
import java.nio.file.Path

/** Test-only process launch settings; production selects the pinned native host. */
public data class LegacyHostLaunch(
    val heapMb: Int = DEFAULT_HEAP_MB,
    val classpath: String = WorkerArtifacts.runningClasspath(),
    val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    val startTimeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
) {
    internal fun options(): HostLaunch = HostLaunch(heapMb, classpath, javaExecutable, spawn, startTimeoutMs)
}

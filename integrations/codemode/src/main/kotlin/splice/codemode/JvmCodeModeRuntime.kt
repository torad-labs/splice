// NEW: session-owned native engines execute on a bounded, on-demand host process pool.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import splice.codemode.host.CodeModeCellStarts
import splice.codemode.host.CodeModeHostLauncher
import splice.codemode.host.CodeModeHostPool
import splice.codemode.host.CodeModeHostStart
import splice.codemode.host.CodeModePoolAdmission
import splice.codemode.host.CodeModePoolLease
import splice.codemode.host.CodeModePoolTimes
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.codemode.ProcessTicker
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

// why: twice the operator's observed ten-session peak needs five four-engine hosts, plus recovery headroom.
public const val DEFAULT_MAX_WORKERS: Int = 6

// why: retained compatibility hint; startup has its own deadline and parked source waits remain caller-owned.
public const val DEFAULT_ADVANCE_TIMEOUT_MS: Long = 5_000

// why: JVM and native-isolate warm-up get a separate boot budget, never a script execution deadline.
public const val DEFAULT_WORKER_START_TIMEOUT_MS: Long = 30_000

// why: each host's protocol JVM has 512 MiB, independent of its sessions' native guest heaps.
public const val DEFAULT_HEAP_MB: Int = 512

// why: twice the observed ten-session peak fits below a 44 GiB memory throttle point on the process's slice.
// Six hosts at 1152 MiB plus 21 engines at 832 MiB reserve 24384 MiB; the 22nd needs 25216 MiB.
public const val DEFAULT_POOL_MEMORY_MB: Long = 24L * 1024

private const val LEGACY_SESSION: String = "unaddressed-runtime"

/** Starts a host at the OS boundary; injectable for ownership and boot measurements. */
public fun interface WorkerSpawn {
    public operator fun invoke(builder: ProcessBuilder): Process
}

/** Sessions own engines and remain pinned to the least-loaded host selected at their first cell. */
public class JvmCodeModeRuntime(
    maxWorkers: Int = DEFAULT_MAX_WORKERS,
    advanceTimeoutMs: Long = DEFAULT_ADVANCE_TIMEOUT_MS,
    private val heapMb: Int = DEFAULT_HEAP_MB,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    workerClasspath: String = WorkerArtifacts.runningClasspath(),
    private val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    private val workerStartTimeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
    idleTimeoutMs: Long = CodeModeHeap.idleTimeoutMs,
    now: ElapsedClock = ProcessElapsedNow(),
    ticker: Ticker = ProcessTicker(),
    memoryBudgetMb: Long = DEFAULT_POOL_MEMORY_MB,
) : CodeModeRuntime {
    @Volatile private var hostLifecycleLog = LogSink {}

    private val closed = AtomicBoolean()
    private val scope = LifecycleScope(ioDispatcher)

    init {
        require(maxWorkers > 0) { "Code-mode host count must be positive" }
        require(advanceTimeoutMs > 0) { "Code-mode advance hint must be positive" }
        require(workerStartTimeoutMs > 0) { "Code-mode worker start timeout must be positive" }
        require(heapMb > 0) { "Code-mode host heap must be positive" }
        require(memoryBudgetMb > 0) { "Code-mode pool memory budget must be positive" }
        require(idleTimeoutMs > 0) { "Code-mode session idle timeout must be positive" }
        require(workerClasspath.isNotBlank()) { "Code-mode worker classpath is required" }
    }

    private val launcher = CodeModeHostLauncher(
        workerClasspath,
        javaExecutable,
        heapMb,
        spawn,
        scope,
        ioDispatcher,
        workerStartTimeoutMs,
    )
    private val pool = CodeModeHostPool(
        CodeModePoolAdmission(maxWorkers, heapMb, memoryBudgetMb),
        scope,
        CodeModeHostStart(launcher::open),
        now,
        ticker,
        CodeModePoolTimes(idleTimeoutMs, workerStartTimeoutMs),
        LogSink { hostLifecycleLog(it) },
    )
    private val cells = CodeModeCellStarts(pool, closed)

    /** Observes bounded host quarantine/replacement events; guest source and results are never included. */
    public fun observeHostLifecycle(log: LogSink) {
        hostLifecycleLog = log
    }

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell =
        startSession(LEGACY_SESSION, source, tools, descriptions)

    override suspend fun startSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = create(sessionKey, CodeModeWire.startFrame(source, tools, descriptions), tools, null)

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = startStreamingSession(LEGACY_SESSION, source, tools, descriptions)

    override suspend fun startStreamingSession(
        sessionKey: String,
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = when (val first = source.read()) {
        is CodeModeSourcePart.Complete -> {
            val sealed = CodeModeScopeSeal.names(source)
            if (sealed.isEmpty()) {
                startSession(sessionKey, first.text, tools, descriptions)
            } else {
                create(
                    sessionKey,
                    StreamingCodeModeWire.completeFrame(first.text, tools, descriptions, sealed),
                    tools,
                    null,
                )
            }
        }
        is CodeModeSourcePart.Failed -> throw IOException(first.error)
        is CodeModeSourcePart.Delta -> create(
            sessionKey,
            StreamingCodeModeWire.startFrame(first.text, tools, descriptions, CodeModeScopeSeal.names(source)),
            tools,
            source,
        )
    }

    private suspend fun create(
        sessionKey: String,
        frame: JsonObject,
        tools: Set<String>,
        source: CodeModeSource?,
    ): CodeModeCell = cells.start(openCell(sessionKey), frame, tools, source)

    // This boundary ends before source exchange: only these failures prove source was never dispatched.
    private suspend fun openCell(sessionKey: String): CodeModePoolLease = Cancellables.runCatchingBestEffort {
        require(sessionKey.isNotBlank()) { "Code-mode session key is required" }
        check(!closed.get()) { "Code-mode runtime is closed" }
        withTimeoutOrNull(workerStartTimeoutMs) { openFromPool(sessionKey) }
            ?: throw CodeModeTimeoutException(workerStartTimeoutMs)
    }.getOrElse { error ->
        when (error) {
            is IllegalArgumentException -> throw error
            is CodeModeCapacityException -> throw error
            is IOException -> throw CodeModeStartException(error)
            is RuntimeException -> throw CodeModeStartException(error)
            else -> throw error
        }
    }

    private suspend fun openFromPool(sessionKey: String): CodeModePoolLease = try {
        pool.open(sessionKey)
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CodeModeWorkerLostException(error)
        throw error
    }

    override fun closeSession(sessionKey: String) {
        pool.closeSession(sessionKey)
    }

    internal suspend fun liveEngines(): Int = pool.metric("count")

    internal suspend fun busyCells(): Int = pool.metric("busy")

    internal fun liveCells(): Int = cells.count()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                pool.close()
                cells.closeAll()
                launcher.close()
            } finally {
                scope.cancel()
            }
        }
    }
}

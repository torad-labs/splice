package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splice.codemode.host.PoolLimits
import splice.codemode.nativehost.NativeCell
import splice.codemode.nativehost.NativeCellAdmission
import splice.codemode.nativehost.NativeCellLease
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeFrames
import splice.codemode.nativehost.NativeHostArtifact
import splice.codemode.nativehost.NativePendingStart
import splice.codemode.nativehost.NativeRequests
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.LifecycleScope
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.failure.CodeModeStartException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Executes full modules in the bundled Codex host while client callbacks retain their permission boundary. */
public class NativeCodeModeRuntime(
    limits: PoolLimits = PoolLimits(),
    cellHeapMb: Int = DEFAULT_HEAP_MB,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    private val artifact: NativeHostArtifact = NativeHostArtifact(),
) : NativeCodeModeLifecycle {
    private val closed = AtomicBoolean()
    private val ownership = Any()
    private val scope = LifecycleScope(ioDispatcher)
    private val opening = Mutex()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val admission = NativeCellAdmission(limits, cellHeapMb)

    @Volatile private var connection: NativeConnection? = null

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell =
        startSession("unaddressed-native-runtime", source, tools, descriptions)

    override suspend fun startSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = startNativeSession(sessionKey, source, tools, descriptions, NativeCodeModeOptions())

    override suspend fun startStreamingSession(
        sessionKey: String,
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = when (val whole = source.read()) {
        is CodeModeSourcePart.Complete -> startSession(sessionKey, whole.text, tools, descriptions)
        is CodeModeSourcePart.Failed -> throw IOException(whole.error)
    }

    override suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell {
        require(sessionKey.isNotBlank()) { "Native code-mode session key is required" }
        require(
            options.maxOutputTokens <= Int.MAX_VALUE,
        ) { "Native execute output budget exceeds protocol V1 i32 range" }
        val lease = admission.reserve()
        var transferred = false
        var started: NativePendingStart? = null
        try {
            val (host, sessionId) = beforeDispatch(sessionKey)
            // A failure from this point can follow execution. It must never authorize a source retry.
            val operation = host.operation(
                NativeRequests.execute(
                    sessionId,
                    source,
                    CodeModeManual.nestedNames(tools).associateWith { descriptions[it].orEmpty() },
                    options,
                ),
                execute = true,
            )
            started = NativePendingStart(host, sessionId, operation)
            val accepted = NativeFrames.value(operation.response.await())
            val cellId = JsonScalars.str(accepted, "cellId") ?: throw IOException("Native host did not accept a cell")
            return NativeCell(cellId, sessionId, host, scope, requireNotNull(operation.initial), tools, lease).also {
                transferred = true
            }
        } finally {
            if (!transferred) abandon(started, lease)
        }
    }

    private fun abandon(started: NativePendingStart?, lease: NativeCellLease) {
        if (started == null) {
            lease.close()
        } else {
            scope.launch {
                try {
                    Cancellables.runCatchingBestEffort {
                        val accepted = NativeFrames.value(started.operation.response.await())
                        val id = requireNotNull(JsonScalars.str(accepted, "cellId"))
                        started.host.operation(NativeRequests.terminate(started.sessionId, id)).response.await()
                    }
                } finally {
                    lease.close()
                }
            }
        }
    }

    private suspend fun beforeDispatch(sessionKey: String): Pair<NativeConnection, String> = try {
        withTimeout(DEFAULT_WORKER_START_TIMEOUT_MS) {
            opening.withLock {
                check(!closed.get()) { "Native code-mode runtime is closed" }
                val host = connection ?: open().also { connection = it }
                val sessionId = sessions[sessionKey] ?: UUID.randomUUID().toString().also { id ->
                    NativeFrames.value(host.operation(NativeRequests.open(id, admission.heapBytes)).response.await())
                    sessions[sessionKey] = id
                }
                host to sessionId
            }
        }
    } catch (error: IOException) {
        throw CodeModeStartException(error)
    }

    private suspend fun open(): NativeConnection = withContext(ioDispatcher) {
        val host = synchronized(ownership) {
            check(!closed.get()) { "Native code-mode runtime is closed" }
            NativeConnection(
                spawn(ProcessBuilder(artifact.resolve().toString(), "--listen", "stdio")),
                scope,
                ioDispatcher,
                Runnable(admission::closeAll),
            ).also { connection = it }
        }
        try {
            host.handshake()
            host
        } catch (error: CancellationException) {
            host.close()
            throw error
        } catch (error: IOException) {
            host.close()
            throw error
        }
    }

    override fun closeSession(sessionKey: String) {
        val sessionId = sessions.remove(sessionKey) ?: return
        val host = connection ?: return
        scope.launch {
            Cancellables.runCatchingBestEffort {
                host.operation(NativeRequests.session("session/shutdown", sessionId)).response.await()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val host = synchronized(ownership) { connection }
            if (host == null) {
                artifact.close()
            } else {
                host.afterExit(Runnable(artifact::close))
                host.close()
            }
            sessions.clear()
            admission.closeAll()
            scope.cancel()
        }
    }
}

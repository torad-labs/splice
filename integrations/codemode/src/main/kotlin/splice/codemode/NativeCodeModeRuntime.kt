package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.codemode.host.PoolLimits
import splice.codemode.nativehost.NativeCell
import splice.codemode.nativehost.NativeCellAdmission
import splice.codemode.nativehost.NativeCellLease
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeConnectionClosed
import splice.codemode.nativehost.NativeFrames
import splice.codemode.nativehost.NativeHostArtifact
import splice.codemode.nativehost.NativeHostLaunch
import splice.codemode.nativehost.NativePendingStart
import splice.codemode.nativehost.NativePreparedSession
import splice.codemode.nativehost.NativeRequests
import splice.codemode.nativehost.sessions.NativeLogicalCell
import splice.codemode.nativehost.sessions.NativeLogicalControl
import splice.codemode.nativehost.sessions.NativeLogicalSession
import splice.codemode.nativehost.sessions.NativeLogicalSessions
import splice.codemode.nativehost.sessions.NativeSessionConnection
import splice.codemode.nativehost.trace.NativeTraceContext
import splice.codemode.nativehost.trace.NativeTracing
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
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeToolException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Executes full modules in the bundled Codex host while client callbacks retain their permission boundary. */
public class NativeCodeModeRuntime(
    limits: PoolLimits = PoolLimits(),
    cellHeapMb: Int = DEFAULT_HEAP_MB,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    private val artifact: NativeHostArtifact = NativeHostArtifact(),
    private val maxYieldTimeMs: Long? = null,
) : NativeCodeModeLifecycle {
    private val closed = AtomicBoolean()
    private val ownership = Any()
    private val scope = LifecycleScope(ioDispatcher)
    private val opening = Mutex()
    private val admission = NativeCellAdmission(limits, cellHeapMb)
    private val sessions =
        NativeLogicalSessions(NativeSessionConnection(::activeConnection), admission.heapBytes, maxYieldTimeMs)

    @Volatile private var connection: NativeConnection? = null

    init {
        require(maxYieldTimeMs == null || maxYieldTimeMs >= 0) { "Native session yield limit must be nonnegative" }
    }

    override suspend fun openNativeSession(sessionKey: String): Unit = NativeTraceContext.preserve {
        require(sessionKey.isNotBlank()) { "Native code-mode session key is required" }
        logicalSession(sessionKey).initialize()
    }

    override suspend fun controlNativeSession(
        sessionKey: String,
        cellId: String,
    ): NativeCodeModeCell = NativeTraceContext.preserve {
        require(sessionKey.isNotBlank()) { "Native code-mode session key is required" }
        val owner = logicalSession(sessionKey)
        owner.initialize()
        NativeLogicalControl(owner, cellId)
    }

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
    ): NativeCodeModeCell = startNativeSession(
        sessionKey,
        source,
        CodeModeManual.nestedNames(tools).map { name ->
            CodeModeManual.NestedTool(name, descriptions[name].orEmpty(), inputSchema = null)
        },
        options,
    )

    override suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        definitions: List<CodeModeManual.NestedTool>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell = NativeTraceContext.preserve {
        require(sessionKey.isNotBlank()) { "Native code-mode session key is required" }
        val prepared = beforeDispatch(sessionKey)
        val lease = prepared.lease
        var transferred = false
        var started: NativePendingStart? = null
        try {
            val host = prepared.host
            val sessionId = prepared.sessionId
            // A failure from this point can follow execution. It must never authorize a source retry.
            val operation = host.prepare(execute = true)
            started = NativePendingStart(host, sessionId, operation)
            host.events.expect(operation.id, sessionId, lease)
            host.dispatch(
                operation,
                NativeRequests.execute(
                    sessionId,
                    source,
                    definitions,
                    options,
                ),
            )
            val accepted = NativeFrames.value(operation.response.await())
            val cellId = JsonScalars.str(accepted, "cellId") ?: throw IOException("Native host did not accept a cell")
            val raw = NativeCell(cellId, sessionId, host, scope, operation, definitions, lease)
            NativeLogicalCell(raw, prepared.owner, prepared.binding, cellId).also {
                transferred = true
            }
        } finally {
            if (!transferred) abandon(started, lease)
        }
    }

    private fun abandon(started: NativePendingStart?, lease: NativeCellLease) {
        if (started == null || !started.operation.wasDispatched()) {
            started?.let { it.host.discard(it.operation) }
            lease.close()
        } else {
            scope.launch {
                try {
                    Cancellables.runCatchingBestEffort {
                        NativeFrames.value(started.operation.response.await())
                        started.host.send(NativeRequests.control("operation/abandon", started.operation.id))
                    }
                } finally {
                    lease.close()
                }
            }
        }
    }

    private suspend fun beforeDispatch(sessionKey: String): NativePreparedSession = try {
        val owner = logicalSession(sessionKey)
        val binding = owner.request()
        NativePreparedSession(owner, binding, admission.reserve())
    } catch (error: CodeModeToolException) {
        throw error
    } catch (error: CodeModeCapacityException) {
        throw error
    } catch (error: IOException) {
        throw CodeModeStartException(error)
    }

    /** The same logical owner is retained by foreground cells, controls and session expiry. */
    internal fun logicalSession(key: String): NativeLogicalSession = sessions.session(key)

    private suspend fun activeConnection(): NativeConnection = opening.withLock {
        val current = connection
        if (current != null && current.isAvailable()) return@withLock current
        current?.close()
        open()
    }

    private fun retire(dead: NativeConnection) {
        synchronized(ownership) {
            if (connection === dead) {
                connection = null
            }
        }
    }

    private suspend fun open(): NativeConnection = withContext(ioDispatcher) {
        val host = synchronized(ownership) {
            check(!closed.get()) { "Native code-mode runtime is closed" }
            NativeConnection(
                spawn(NativeHostLaunch.command(artifact.resolve())),
                scope,
                ioDispatcher,
                NativeConnectionClosed(::retire),
                NativeTracing(),
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
        val session = sessions.remove(sessionKey) ?: return
        scope.launch {
            Cancellables.runCatchingBestEffort {
                session.host.operation(NativeRequests.session("session/shutdown", session.id)).response.await()
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
            sessions.close()
            admission.closeAll()
            scope.cancel()
        }
    }
}

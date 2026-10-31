package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.codemode.host.PoolLimits
import splice.codemode.nativehost.NativeCellAdmission
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeConnectionClosed
import splice.codemode.nativehost.NativeHostArtifact
import splice.codemode.nativehost.NativeHostLaunch
import splice.codemode.nativehost.NativeRequests
import splice.codemode.nativehost.sessions.NativeLogicalControl
import splice.codemode.nativehost.sessions.NativeLogicalSession
import splice.codemode.nativehost.sessions.NativeLogicalSessions
import splice.codemode.nativehost.sessions.NativeSessionConnection
import splice.codemode.nativehost.trace.NativeTraceContext
import splice.codemode.nativehost.trace.NativeTracing
import splice.core.util.Cancellables
import splice.upstream.LifecycleScope
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.codemode.ProcessDispatchers
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
    private val starter = NativeCellStarter(scope, admission, sessions)

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
        starter.start(sessionKey, source, definitions, options)
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

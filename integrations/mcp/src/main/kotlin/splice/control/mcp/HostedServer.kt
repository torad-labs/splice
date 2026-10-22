// NEW: v0.4.0 FEATURES.md §8 — one child MCP server process, spoken to over newline-delimited
// JSON-RPC on its stdio, shared by any number of client sessions. The host initializes the child
// ONCE with its own identity; every client session later receives that cached handshake. Request
// ids are remapped per call so two sessions using the same client-side id can never collide, and
// every answer is routed back by the host id it was sent under — never by guesswork.
//
// Server-initiated requests (sampling, elicitation, roots) cannot be multiplexed to one client
// honestly, so the host answers them itself: `ping` -> {}, `roots/list` -> no roots, anything
// else -> method-not-found. The host offers roots in its handshake so a server that depends on them
// asks: the ask is reported, and its launch tuple launches per session from the next session on.
// A server needing sampling or elicitation is not a sharing candidate; the operator excludes it.
package splice.control.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.mcp.McpServerSpec
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Starts one child while its registry still owns the right to do so. */
internal fun interface McpLaunchGate {
    fun launch(spec: McpServerSpec, launcher: McpProcessLauncher): Process?
}

/** The process services a hosted child needs throughout one owned lifetime. */
internal data class HostedServerRuntime(
    val config: McpHostConfig,
    val launcher: McpProcessLauncher,
    val reaped: ChildReaped,
    val launches: McpLaunchGate,
)

internal fun interface McpReadyWriteTarget {
    fun target(): McpWriteTarget?
}

/** Keeps the replacement fence separate from the host's normal request lifecycle. */
internal class HostedServerReplacement(
    private val writer: McpChildWriter,
    private val target: McpReadyWriteTarget,
) {
    fun fence(): McpReplacementFence? {
        val selected = target.target() ?: return null
        return if (writer.seal(selected)) McpReplacementFence(writer, selected) else null
    }
}

private fun interface HostedHandshakePublication {
    suspend fun publish(process: Process, result: JsonObject): Boolean
}

private sealed class HostedHandshakeResult {
    data class Served(val result: JsonObject) : HostedHandshakeResult()
    data class Refused(val reason: String, val reply: McpResult.Refused) : HostedHandshakeResult()
}

/** Executes the bounded initialize exchange without adding branch pressure to process lifecycle ownership. */
private class HostedHandshake(
    private val name: String,
    private val config: McpHostConfig,
    private val codec: JsonRpcCodec,
    private val ids: AtomicLong,
    private val pending: ConcurrentHashMap<Long, Pending>,
    private val writer: McpChildWriter,
    private val publication: HostedHandshakePublication,
) {
    suspend fun run(process: Process): HostedHandshakeResult {
        val id = ids.getAndIncrement()
        val slot = Pending("host", JsonPrimitive(id))
        pending[id] = slot
        val delivered = writer.request(codec.initializeRequest(id), id, process, config.initializeTimeout())
        val answer = if (delivered) await(slot) else null
        if (answer == null) return unavailable(id, delivered)
        val result = answer["result"] as? JsonObject
        return if (result != null && publication.publish(process, result)) {
            HostedHandshakeResult.Served(result)
        } else {
            HostedHandshakeResult.Refused(
                "handshake failed",
                McpResult.Refused("'$name' rejected the MCP handshake (server detail withheld)"),
            )
        }
    }

    private suspend fun await(slot: Pending): JsonObject? =
        withTimeoutOrNull(config.initializeTimeout()) { slot.answer.await() }

    private fun unavailable(id: Long, delivered: Boolean): HostedHandshakeResult.Refused {
        pending.remove(id)
        val reason = if (delivered) "no initialize answer" else "could not write initialize"
        return HostedHandshakeResult.Refused(
            reason,
            McpResult.Refused("'$name' did not complete the MCP handshake", timedOut = true),
        )
    }
}

internal class HostedServer(
    private val spec: McpServerSpec,
    private val runtime: HostedServerRuntime,
    private val codec: JsonRpcCodec,
    private val log: LogSink,
    private val sink: NotificationSink,
) {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    val unsettled: List<Long> get() = send.unsettled
    var admission = McpAdmission()
    private val progress = ProgressTokens()
    private val stops = McpProcessStop()
    private val stderr = McpStderr(log)

    private val history = McpChildHistory(runtime.config.clock, spec.name)

    /** Single-flight spawn: N sessions initializing at once must share ONE child, not race N up
     *  (the 4-session benchmark caught exactly that on 2026-09-13: four copies per server). */
    private val spawnLock = Mutex()

    /** Guards process/initResult so a pump ending an OLD child can never clear its replacement, and
     *  a spawn that lands after close() tears its child down instead of running orphaned. */
    private val stateLock = Any()

    @Volatile private var closed = false

    @Volatile private var process: Process? = null

    /** The immutable stdin writer for [process]'s exact generation. */
    @Volatile private var writer: McpWriteTarget? = null

    @Volatile private var initResult: JsonObject? = null

    private val tools = ToolsListCache(codec)
    private val send = McpChildWriter(
        spec.name,
        codec,
        McpWriteTargetSource { expected ->
            synchronized(stateLock) {
                val child = process
                val target = writer
                when {
                    closed || child == null || target == null -> null
                    child !== target.child -> null
                    expected != null && child !== expected -> null
                    else -> target
                }
            }
        },
        McpWriteFailure(::transportFailed),
    )
    private val handshake = HostedHandshake(
        spec.name,
        runtime.config,
        codec,
        ids,
        pending,
        send,
        HostedHandshakePublication(::publish),
    )

    /** Closes new work on this live generation and atomically proves that no old wire operation remains. */
    internal val replacementFence = HostedServerReplacement(
        send,
        McpReadyWriteTarget {
            synchronized(stateLock) {
                val child = process
                writer?.takeIf { child === it.child && child.isAlive && initResult != null }
            }
        },
    )
    private val serverRequests = HostedServerRequests(
        spec.name,
        ServerRequestId(ids::getAndIncrement),
        codec,
        sink,
        send,
        runtime.config.requestTimeout,
    )

    @Volatile var lastError: String? = null
        private set

    val alive: Boolean get() = process?.isAlive == true && initResult != null

    /** When the current child was started (epoch ms), or null before the first start. */
    val startedAt: Long? get() = history.startedAt.takeIf { it > 0L }

    /** The launch tuple this process serves; the registry keys reservations by it. */
    val identity: McpIdentity get() = McpIdentity(spec.command, spec.args, spec.env)
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    /** The child's initialize result, spawning and handshaking first when the child is not up. */
    suspend fun ensureStarted(): McpResult<JsonObject> = started?.let { McpResult.Served(it) } ?: spawnLock.withLock {
        if (closed) {
            McpResult.Refused("hosted MCP server '${spec.name}' was closed")
        } else {
            started?.let { McpResult.Served(it) } ?: spawn()
        }
    }

    private val started: JsonObject?
        get() = initResult?.takeIf { process?.isAlive == true }

    /** Forward one request; the answer comes back under [clientId] no matter how the child numbered it. */
    suspend fun call(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
        when (val up = ensureStarted()) {
            is McpResult.Refused -> return codec.error(clientId, RPC_SERVER_ERROR, up.reason)
            is McpResult.Served -> Unit
        }
        return tools.answer(request, clientId, ListingAsk { forward(sessionId, clientId, request) })
    }

    private suspend fun forward(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
        val hostId = ids.getAndIncrement()
        val (out, token) = progress.outbound(request, hostId)
        val child = synchronized(stateLock) { process?.takeIf { it.isAlive && initResult != null } }
            ?: return codec.error(clientId, RPC_SERVER_EXITED, "hosted MCP server '${spec.name}' is not ready")
        val slot = Pending(sessionId, clientId, token)
        pending[hostId] = slot
        if (!send.request(codec.withId(out, JsonPrimitive(hostId)), hostId, child, runtime.config.requestTimeout())) {
            pending.remove(hostId)
            return codec.error(
                clientId,
                RPC_SERVER_EXITED,
                "request delivery was not confirmed; outcome unknown, not replayed",
            )
        }
        // withTimeoutOrNull, not withTimeout+catch: an OUTER cancellation (the client went away, a
        // caller's own deadline) is a TimeoutCancellationException too and must propagate, never be
        // read as "the child did not answer" (review 4 regression, 2026-09-13).
        // ONE reading for this request: the budget it waits under is the budget it was admitted under.
        return withTimeoutOrNull(runtime.config.requestTimeout()) { slot.answer.await() } ?: run {
            pending.remove(hostId)
            codec.error(clientId, RPC_TIMED_OUT, "hosted MCP server '${spec.name}' did not answer in time")
        }
    }

    /** Forward a client notification: initialized is the host's own (swallowed), cancelled is remapped
     *  to the host id of THAT session's request and dropped when it names none — a cancel must never
     *  reach the child under another session's id. False when the child could not be written. */
    suspend fun notify(sessionId: String, msg: JsonObject): McpResult<Boolean> {
        if (codec.method(msg) == "notifications/initialized") return McpResult.Served(true)
        return when (val up = ensureStarted()) {
            is McpResult.Refused -> up
            is McpResult.Served -> {
                val out = if (codec.method(msg) == "notifications/cancelled") {
                    progress.cancellation(sessionId, msg, pending, codec)
                } else {
                    msg
                }
                val child = synchronized(stateLock) { process?.takeIf { it.isAlive && initResult != null } }
                when {
                    out == null -> McpResult.Served(true)
                    child == null -> McpResult.Refused("hosted MCP server '${spec.name}' is not ready")
                    else -> McpResult.Served(send.notify(out, child, runtime.config.requestTimeout()))
                }
            }
        }
    }

    /** Permanent: the registry replaced or evicted this server; a spawn racing this call tears down. */
    fun close(reason: String, wait: Boolean = true): Process? {
        synchronized(stateLock) { closed = true }
        serverRequests.close()
        val stopped = tearDown(reason, wait)
        send.close()
        return stopped
    }

    private fun tearDown(reason: String, wait: Boolean = true): Process? {
        val (p, target) = synchronized(stateLock) {
            val stopped = process
            val selected = writer
            process = null
            writer = null
            initResult = null
            tools.drop()
            stopped to selected
        }
        target?.let(send::retire)
        failPending("hosted MCP server '${spec.name}' closed: $reason")
        p?.let {
            log("[mcp-host] ${spec.name}: closing pid ${it.pid()} ($reason)\n")
            if (wait) {
                stops.stop(listOf(it))
                target?.let(send::exited)
            }
        }
        return p
    }

    private suspend fun spawn(): McpResult<JsonObject> {
        val stopping = synchronized(stateLock) { process != null }
        val admission = if (stopping) null else history.admit()
        return when {
            stopping -> McpResult.Refused("hosted MCP server '${spec.name}' is stopping after a transport failure")
            admission != null -> admission
            else -> when (val launched = launch()) {
                is McpResult.Refused -> launched
                is McpResult.Served -> try {
                    completeHandshake(launched.value, handshake.run(launched.value))
                } catch (e: CancellationException) {
                    tearDown("cancelled during the handshake")
                    throw e
                }
            }
        }
    }

    /** The child, adopted under [stateLock] — or torn down at once when close() won the race. */
    private fun launch(): McpResult<Process> {
        var spawnFailure: String? = null
        val child = try {
            runtime.launches.launch(spec, runtime.launcher)
        } catch (failure: IOException) {
            spawnFailure = failure.message
            null
        }
        if (child == null) {
            val reason = spawnFailure?.let { "cannot start '${spec.name}': $it" }
                ?: "MCP host is stopping"
            spawnFailure?.let { lastError = "spawn failed: $it" }
            return McpResult.Refused(reason)
        }
        val adopted = synchronized(stateLock) {
            if (closed) {
                child.destroyForcibly()
                false
            } else {
                process = child
                writer = McpWriteTarget(child, child.outputStream.bufferedWriter())
                true
            }
        }
        return if (adopted) {
            stderr.watch(spec.name, child)
            history.started()
            Executors.defaultThreadFactory().newThread { pump(child) }.apply {
                name = "mcp-host-${spec.name}"
                isDaemon = true
            }.start()
            McpResult.Served(child)
        } else {
            McpResult.Refused("hosted MCP server '${spec.name}' was closed while starting")
        }
    }

    private fun completeHandshake(process: Process, outcome: HostedHandshakeResult): McpResult<JsonObject> =
        when (outcome) {
            is HostedHandshakeResult.Served -> {
                lastError = null
                log("[mcp-host] ${spec.name}: hosted as pid ${process.pid()}\n")
                McpResult.Served(outcome.result)
            }
            is HostedHandshakeResult.Refused -> {
                tearDown(outcome.reason)
                outcome.reply
            }
        }

    /** The child is operational only once it has been told `initialized`: send that FIRST, then
     *  publish under stateLock, and only while THIS child is still the adopted, live, unclosed one —
     *  a close() landing between the answer and the publish must win (reviews 2-3, 2026-09-13), or a
     *  session would be minted on a server that is dead, unbound, or not yet serving. */
    private suspend fun publish(p: Process, result: JsonObject): Boolean {
        val initialized = send.notify(
            codec.notification("notifications/initialized"),
            p,
            runtime.config.initializeTimeout(),
        )
        return synchronized(stateLock) {
            (initialized && !closed && process === p && p.isAlive).also { if (it) initResult = result }
        }
    }

    /** A failed pipe may have delivered bytes. Never refund its wire IDs until that exact child exits. */
    private fun transportFailed(child: Process, reason: String) {
        val target = synchronized(stateLock) {
            writer?.takeIf { process === child && it.child === child }?.also {
                lastError = "$reason; delivery outcome unknown, operation was not replayed"
                writer = null
                initResult = null
                tools.drop()
            }
        } ?: return
        send.retire(target)
        failPending("hosted MCP transport failed; operation outcome unknown, not replayed")
        stops.stop(listOf(child))
        val exitCode = runtime.reaped.codeOf(child)
        synchronized(stateLock) { if (process === child) process = null }
        send.exited(target)
        history.exited()
        log(
            "[mcp-host] ${LogSafe.str(spec.name)}: pid ${child.pid()} exited ${LogSafe.str(exitCode)} " +
                "(${LogSafe.str(lastError.orEmpty())})\n",
        )
    }

    /** The reader thread: one line, one message, until EOF, which fails this child's transport. */
    private fun pump(p: Process) {
        val reader: BufferedReader = p.inputStream.bufferedReader()
        val reason = try {
            generateSequence { reader.readLine() }
                .filter { it.isNotBlank() }
                .mapNotNull(codec::parse)
                .forEach { dispatch(p, it) }
            "stdout closed"
        } catch (e: IOException) {
            "read failed: ${e.message}"
        }
        transportFailed(p, reason)
    }

    private fun dispatch(child: Process, msg: JsonObject) {
        when (codec.kind(msg)) {
            RpcKind.RESPONSE -> {
                val hostId = JsonScalars.str(msg["id"])?.toLongOrNull()
                hostId?.let(send::settled)
                val slot = hostId?.let(pending::remove)
                slot?.answer?.complete(codec.withId(msg, slot.clientId))
            }
            RpcKind.REQUEST -> serverRequests.dispatch(child, msg)
            RpcKind.NOTIFICATION -> if (codec.method(msg) == "notifications/progress") {
                // A missing owner is late or unknown progress, never a global notification.
                progress.owner(msg, pending)?.let { (slot, routed) -> sink.onProgress(slot.sessionId, routed) }
            } else {
                if (codec.method(msg) == "notifications/tools/list_changed") tools.drop()
                sink.onNotification(msg)
            }
            RpcKind.INVALID -> Unit
        }
    }

    /** Fails what is in flight — everything, or only [sessionId]'s requests (its session ended). */
    fun failPending(message: String, sessionId: String? = null) {
        pending.entries
            .filter { sessionId == null || it.value.sessionId == sessionId }
            .map { it.key }
            .forEach { id -> pending.remove(id)?.fail(codec, message) }
    }
}

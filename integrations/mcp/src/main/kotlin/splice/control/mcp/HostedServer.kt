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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Adopts a child while the launch barrier still prevents daemon shutdown from passing it. */
internal fun interface McpLaunchAdoption {
    fun adopt(process: Process, generation: McpProcessGeneration): Boolean
}

/** Starts and adopts one child while its registry still owns the right to do so. */
internal fun interface McpLaunchGate {
    fun launch(spec: McpServerSpec, launcher: McpProcessLauncher, adoption: McpLaunchAdoption): Process?
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

/** The initialize result and the exact child generation that produced it. */
internal data class HostedInitialization(
    val result: JsonObject,
    val generation: McpProcessGeneration,
)

private fun interface HostedHandshakePublication {
    suspend fun publish(process: Process, result: JsonObject, budget: Duration): HostedInitialization?
}

private class McpDeadline(
    private val clock: HostClock,
    budget: Duration,
) {
    private val expiresAt = clock.millis() + budget.inWholeMilliseconds

    fun remaining(): Duration = (expiresAt - clock.millis()).coerceAtLeast(0L).milliseconds
}

private sealed class HostedHandshakeResult {
    data class Served(val initialization: HostedInitialization) : HostedHandshakeResult()
    data class Refused(val reason: String, val reply: McpResult.Refused) : HostedHandshakeResult()
}

private fun interface HostedClientTarget {
    fun target(): McpWriteTarget?
}

private data class HostedClientRuntime(
    val config: McpHostConfig,
    val codec: JsonRpcCodec,
    val writer: McpChildWriter,
    val target: HostedClientTarget,
)

private data class HostedForward(
    val hostId: Long,
    val child: Process,
    val request: JsonObject,
    val pending: Pending,
    val clientId: JsonElement,
)

/** Owns client request and notification forwarding once the host has ensured the child is started. */
private class HostedClientCalls(
    private val name: String,
    private val runtime: HostedClientRuntime,
    private val ids: AtomicLong,
    private val pending: ConcurrentHashMap<Long, Pending>,
    private val progress: ProgressTokens,
) {
    suspend fun forward(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
        if (pending.values.any { it.matches(sessionId, clientId) }) {
            return runtime.codec.error(clientId, RPC_SERVER_EXITED, "request id is already in flight for this session")
        }
        val deadline = McpDeadline(runtime.config.clock, runtime.config.requestTimeout())
        val hostId = ids.getAndIncrement()
        val (out, token) = progress.outbound(request, hostId)
        val child = runtime.target.target()?.child
            ?: return runtime.codec.error(clientId, RPC_SERVER_EXITED, "hosted MCP server '$name' is not ready")
        val slot = Pending(sessionId, clientId, token)
        val forwarded = HostedForward(hostId, child, out, slot, clientId)
        pending[hostId] = slot
        return try {
            awaitAnswer(forwarded, deadline)
        } catch (error: CancellationException) {
            slot.terminateProgress()
            throw error
        }
    }

    private suspend fun awaitAnswer(forwarded: HostedForward, deadline: McpDeadline): JsonObject {
        val writeBudget = deadline.remaining()
        if (writeBudget <= Duration.ZERO) {
            pending.remove(forwarded.hostId, forwarded.pending)
            return timedOut(forwarded.clientId, forwarded.pending)
        }
        val delivered = runtime.writer.request(
            runtime.codec.withId(forwarded.request, JsonPrimitive(forwarded.hostId)),
            forwarded.hostId,
            forwarded.child,
            writeBudget,
        )
        val answerBudget = deadline.remaining()
        // A charged wire ID remains cancelable until this exact generation settles it or exits.
        return when {
            !delivered -> deliveryRefused(forwarded.hostId, forwarded.pending, forwarded.clientId)
            answerBudget <= Duration.ZERO -> timedOut(forwarded.clientId, forwarded.pending)
            else -> withTimeoutOrNull(answerBudget) { forwarded.pending.answer.await() }
                ?: timedOut(forwarded.clientId, forwarded.pending)
        }
    }

    suspend fun notify(sessionId: String, msg: JsonObject): McpResult<Boolean> {
        val out = if (runtime.codec.method(msg) == "notifications/cancelled") {
            progress.cancellation(sessionId, msg, pending, runtime.codec)
        } else {
            msg
        }
        val child = runtime.target.target()?.child
        return when {
            out == null -> McpResult.Served(true)
            child == null -> McpResult.Refused("hosted MCP server '$name' is not ready")
            else -> McpResult.Served(runtime.writer.notify(out, child, runtime.config.requestTimeout()))
        }
    }

    private fun deliveryRefused(hostId: Long, slot: Pending, clientId: JsonElement): JsonObject {
        // A writer admission refusal never owned this wire id. A failed write keeps it charged for its
        // exact generation, so cancellation and eventual child-exit cleanup remain available.
        if (hostId !in runtime.writer.unsettled) pending.remove(hostId, slot)
        return runtime.codec.error(
            clientId,
            RPC_SERVER_EXITED,
            "request delivery was not confirmed; outcome unknown, not replayed",
        )
    }

    private fun timedOut(clientId: JsonElement, slot: Pending): JsonObject {
        slot.terminateProgress()
        return runtime.codec.error(clientId, RPC_TIMED_OUT, "hosted MCP server '$name' did not answer in time")
    }
}

/** Proves a decoded stdout message still belongs to the active child generation. */
private fun interface HostedServerGeneration {
    fun current(child: Process): Boolean
}

private data class HostedServerDispatchRuntime(
    val codec: JsonRpcCodec,
    val writer: McpChildWriter,
    val serverRequests: HostedServerRequests,
    val sink: NotificationSink,
)

/** Dispatches messages from one stdout generation only after its owner proves it is still current. */
private class HostedServerDispatch(
    private val runtime: HostedServerDispatchRuntime,
    private val progress: ProgressTokens,
    private val pending: ConcurrentHashMap<Long, Pending>,
    private val tools: ToolsListCache,
    private val generation: HostedServerGeneration,
) {
    fun dispatch(child: Process, msg: JsonObject) {
        when (runtime.codec.kind(msg)) {
            RpcKind.RESPONSE -> response(msg)
            RpcKind.REQUEST -> runtime.serverRequests.dispatch(child, msg)
            RpcKind.NOTIFICATION -> notification(child, msg)
            RpcKind.INVALID -> Unit
        }
    }

    private fun response(msg: JsonObject) {
        val hostId = JsonScalars.str(msg["id"])?.toLongOrNull()
        hostId?.let(runtime.writer.settled::invoke)
        val slot = hostId?.let(pending::remove)
        slot?.answer?.complete(runtime.codec.withId(msg, slot.clientId))
    }

    private fun notification(child: Process, msg: JsonObject) {
        if (!generation.current(child)) return
        if (runtime.codec.method(msg) == "notifications/progress") {
            // A missing owner is late or unknown progress, never a global notification.
            progress.owner(msg, pending)?.let { (slot, routed) ->
                runtime.sink.onProgress(child, slot.sessionId, routed)
            }
        } else {
            ordinaryNotification(child, msg)
        }
    }

    private fun ordinaryNotification(child: Process, msg: JsonObject) {
        if (runtime.codec.method(msg) == "notifications/tools/list_changed") tools.drop()
        runtime.sink.onNotification(child, msg)
    }
}

/** Rechecks retained scope ownership before the server may reuse a child slot after a failed transport. */
private class HostedGenerationRetention(
    private val stops: McpProcessStop,
    private val reaped: ChildReaped,
) {
    fun settled(process: Process, generation: McpProcessGeneration): Boolean {
        stops.stopGenerations(emptyList())
        return reaped(process) && !stops.retains(generation)
    }
}

/** One retired generation retains its child wire charges until its exact scope proves empty. */
private data class HostedRetiredChild(
    val target: McpWriteTarget,
    val generation: McpProcessGeneration,
)

private data class HostedStoppingChild(
    val process: Process,
    val generation: McpProcessGeneration,
    val retired: McpWriteTarget? = null,
)

private fun interface HostedFailureRetirement {
    fun retire(child: Process, reason: String): HostedRetiredChild?
}

private fun interface HostedFailureRelease {
    fun release(child: Process, generation: McpProcessGeneration): Boolean
}

private fun interface HostedPendingFailure {
    fun fail(message: String)
}

internal fun interface HostedNotificationEnqueue {
    fun enqueue()
}

private fun interface HostedFailureDescription {
    fun describe(): String
}

private data class HostedTransportRuntime(
    val name: String,
    val send: McpChildWriter,
    val stops: McpProcessStop,
    val reaped: ChildReaped,
    val history: McpChildHistory,
    val log: LogSink,
)

private data class HostedTransportActions(
    val retire: HostedFailureRetirement,
    val release: HostedFailureRelease,
    val failPending: HostedPendingFailure,
    val description: HostedFailureDescription,
)

private fun interface HostedSpawnReadiness {
    fun ready(): Boolean
}

private fun interface HostedChildLaunch {
    fun launch(): McpResult<Process>
}

private fun interface HostedSpawnHandshake {
    suspend fun handshake(process: Process): McpResult<HostedInitialization>
}

private fun interface HostedSpawnTearDown {
    fun tearDown(reason: String)
}

/** Owns bounded failure retirement without making the server's request entry point branch on process state. */
private class HostedTransportFailure(
    private val runtime: HostedTransportRuntime,
    private val actions: HostedTransportActions,
) {
    fun failed(child: Process, reason: String) {
        val retired = actions.retire.retire(child, reason) ?: return
        runtime.send.retire(retired.target)
        actions.failPending.fail("hosted MCP transport failed; operation outcome unknown, not replayed")
        runtime.stops.stopGenerations(listOf(retired.generation))
        val reaped = runtime.reaped(child)
        val exactExit = reaped && !runtime.stops.retains(retired.generation)
        if (exactExit && actions.release.release(child, retired.generation)) {
            runtime.send.exited(retired.target)
            runtime.history.exited()
        }
        val exitCode = if (reaped) child.exitValue().toString() else "unknown"
        runtime.log(
            "[mcp-host] ${LogSafe.str(runtime.name)}: pid ${child.pid()} exited ${LogSafe.str(exitCode)} " +
                "(${LogSafe.str(actions.description.describe())})\n",
        )
    }
}

/** Starts a child only after retained transport ownership is gone, then keeps cancellation teardown exact. */
private fun interface HostedRetiredRelease {
    fun release(stopping: HostedStoppingChild): McpWriteTarget?
}

/** Releases a retained generation only once its exact process and containment have exited. */
private class HostedStoppedRelease(
    private val retention: HostedGenerationRetention,
    private val send: McpChildWriter,
    private val history: McpChildHistory,
    private val release: HostedRetiredRelease,
) {
    fun release(stopping: HostedStoppingChild) {
        if (!retention.settled(stopping.process, stopping.generation)) return
        release.release(stopping)?.let {
            send.exited(it)
            history.exited()
        }
    }
}

private class HostedSpawn(
    private val name: String,
    private val history: McpChildHistory,
    private val ready: HostedSpawnReadiness,
    private val launch: HostedChildLaunch,
    private val handshake: HostedSpawnHandshake,
    private val tearDown: HostedSpawnTearDown,
) {
    suspend fun start(): McpResult<HostedInitialization> {
        if (!ready.ready()) return McpResult.Refused("hosted MCP server '$name' is stopping after a transport failure")
        return history.admit() ?: afterLaunch()
    }

    private suspend fun afterLaunch(): McpResult<HostedInitialization> {
        val child = when (val launched = launch.launch()) {
            is McpResult.Refused -> return launched
            is McpResult.Served -> launched.value
        }
        return try {
            handshake.handshake(child)
        } catch (error: CancellationException) {
            tearDown.tearDown("cancelled during the handshake")
            throw error
        }
    }
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
        val deadline = McpDeadline(config.clock, config.initializeTimeout())
        val id = ids.getAndIncrement()
        val slot = Pending("host", JsonPrimitive(id))
        pending[id] = slot
        val writeBudget = deadline.remaining()
        val delivered = writeBudget > Duration.ZERO &&
            writer.request(codec.initializeRequest(id), id, process, writeBudget)
        val answer = if (delivered) await(slot, deadline.remaining()) else null
        if (answer == null) return unavailable(id, delivered)
        val result = answer["result"] as? JsonObject
        val initialization = result?.let { publication.publish(process, it, deadline.remaining()) }
        return initialization?.let(HostedHandshakeResult::Served)
            ?: HostedHandshakeResult.Refused(
                "handshake failed",
                McpResult.Refused("'$name' rejected the MCP handshake (server detail withheld)"),
            )
    }

    private suspend fun await(slot: Pending, budget: Duration): JsonObject? =
        if (budget > Duration.ZERO) withTimeoutOrNull(budget) { slot.answer.await() } else null

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
    private val retention = HostedGenerationRetention(stops, runtime.reaped)
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

    /** The process tree and cgroup captured at adoption, before its launcher parent can reparent workers. */
    @Volatile private var generation: McpProcessGeneration? = null

    /** A retired writer's unknown wire charges clear only after its retained generation proves empty. */
    @Volatile private var retiredWriter: McpWriteTarget? = null

    @Volatile private var initResult: JsonObject? = null

    private val tools = ToolsListCache(codec)
    private val send: McpChildWriter = McpChildWriter(
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
        McpWriteFailure { child, reason -> transportFailures.failed(child, reason) },
    )
    private val transportFailures: HostedTransportFailure = HostedTransportFailure(
        HostedTransportRuntime(spec.name, send, stops, runtime.reaped, history, log),
        HostedTransportActions(
            HostedFailureRetirement { child, reason ->
                synchronized(stateLock) {
                    val target = writer
                    if (process !== child || target?.child !== child) {
                        null
                    } else {
                        lastError = "$reason; delivery outcome unknown, operation was not replayed"
                        writer = null
                        retiredWriter = target
                        initResult = null
                        tools.drop()
                        HostedRetiredChild(target, requireNotNull(generation))
                    }
                }
            },
            HostedFailureRelease { child, owned ->
                synchronized(stateLock) {
                    if (process !== child || generation !== owned) {
                        false
                    } else {
                        process = null
                        generation = null
                        retiredWriter = null
                        true
                    }
                }
            },
            HostedPendingFailure { message -> failPending(message) },
            HostedFailureDescription { lastError.orEmpty() },
        ),
    )
    private val clientCalls = HostedClientCalls(
        spec.name,
        HostedClientRuntime(
            runtime.config,
            codec,
            send,
            HostedClientTarget {
                synchronized(stateLock) {
                    writer?.takeIf { process === it.child && it.child.isAlive && initResult != null }
                }
            },
        ),
        ids,
        pending,
        progress,
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
    private val stoppedRelease = HostedStoppedRelease(
        retention,
        send,
        history,
        HostedRetiredRelease { stopping ->
            synchronized(stateLock) {
                if (process !== stopping.process || generation !== stopping.generation) {
                    null
                } else {
                    process = null
                    generation = null
                    retiredWriter.also { retiredWriter = null }
                }
            }
        },
    )
    private val spawner = HostedSpawn(
        spec.name,
        history,
        HostedSpawnReadiness {
            val stopping = synchronized(stateLock) {
                val child = process
                val owned = generation
                if (writer != null) {
                    null
                } else {
                    child?.let { owned?.let { generation -> HostedStoppingChild(it, generation) } }
                }
            }
            stopping?.let(stoppedRelease::release)
            synchronized(stateLock) { process == null }
        },
        HostedChildLaunch(::launch),
        HostedSpawnHandshake { child -> completeHandshake(child, handshake.run(child)) },
        HostedSpawnTearDown { reason -> tearDown(reason) },
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
    private val dispatcher = HostedServerDispatch(
        HostedServerDispatchRuntime(codec, send, serverRequests, sink),
        progress,
        pending,
        tools,
        HostedServerGeneration { child ->
            synchronized(stateLock) {
                if (closed || process !== child) false else writer?.child === child
            }
        },
    )

    @Volatile var lastError: String? = null
        private set

    val alive: Boolean get() = process?.isAlive == true && initResult != null

    /** When the current child was started (epoch ms), or null before the first start. */
    val startedAt: Long? get() = history.startedAt.takeIf { it > 0L }

    /** The launch tuple this process serves; the registry keys reservations by it. */
    val identity: McpIdentity get() = McpIdentity(spec.command, spec.args, spec.env)
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    /** Holds the generation fence until [enqueue] has accepted this child notification. */
    internal fun enqueueIfOwned(child: Process, enqueue: HostedNotificationEnqueue) = synchronized(stateLock) {
        if (process !== child) return@synchronized
        if (writer?.child !== child) return@synchronized
        if (generation?.process === child) enqueue.enqueue()
    }

    internal fun owns(initialization: HostedInitialization): Boolean = synchronized(stateLock) {
        process === initialization.generation.process &&
            generation === initialization.generation &&
            writer?.child === process &&
            initResult === initialization.result
    }

    /** The child's initialize result, spawning and handshaking first when the child is not up. */
    suspend fun ensureStarted(): McpResult<JsonObject> = when (val started = ensureInitialized()) {
        is McpResult.Refused -> started
        is McpResult.Served -> McpResult.Served(started.value.result)
    }

    /** Keeps the exact generation that supplied initialization for registry-bound session publication. */
    suspend fun ensureInitialized(): McpResult<HostedInitialization> =
        started?.let { McpResult.Served(it) } ?: spawnLock.withLock {
            if (closed) {
                McpResult.Refused("hosted MCP server '${spec.name}' was closed")
            } else {
                started?.let { McpResult.Served(it) } ?: spawner.start()
            }
        }

    private val started: HostedInitialization?
        get() = synchronized(stateLock) {
            val result = initResult ?: return@synchronized null
            val owned = generation ?: return@synchronized null
            if (process?.isAlive != true) null else HostedInitialization(result, owned)
        }

    /** Forward one request; the answer comes back under [clientId] no matter how the child numbered it. */
    suspend fun call(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
        when (val up = ensureStarted()) {
            is McpResult.Refused -> return codec.error(clientId, RPC_SERVER_ERROR, up.reason)
            is McpResult.Served -> Unit
        }
        return tools.answer(request, clientId, ListingAsk { clientCalls.forward(sessionId, clientId, request) })
    }

    /** Forward a client notification: initialized is the host's own (swallowed), cancelled is remapped
     *  to the host id of THAT session's request and dropped when it names none — a cancel must never
     *  reach the child under another session's id. False when the child could not be written. */
    suspend fun notify(sessionId: String, msg: JsonObject): McpResult<Boolean> {
        if (codec.method(msg) == "notifications/initialized") return McpResult.Served(true)
        return when (val up = ensureStarted()) {
            is McpResult.Refused -> up
            is McpResult.Served -> clientCalls.notify(sessionId, msg)
        }
    }

    /** Permanent: the registry replaced or evicted this server; a spawn racing this call tears down. */
    fun close(reason: String, wait: Boolean = true): McpProcessGeneration? {
        synchronized(stateLock) { closed = true }
        serverRequests.close()
        val stopped = tearDown(reason, wait)
        send.close()
        return stopped
    }

    private fun tearDown(reason: String, wait: Boolean = true): McpProcessGeneration? {
        val stopping = synchronized(stateLock) {
            val child = process
            val owned = generation ?: child?.let(stops::capture)?.also { generation = it }
            val selected = writer
            writer = null
            if (selected != null) retiredWriter = selected
            initResult = null
            tools.drop()
            child?.let { running -> owned?.let { HostedStoppingChild(running, it, selected) } }
        }
        stopping?.let {
            it.retired?.let(send::retire)
            failPending("hosted MCP server '${spec.name}' closed: $reason")
            log(
                "[mcp-host] ${LogSafe.str(spec.name)}: closing pid ${LogSafe.str(it.process.pid().toString())} " +
                    "(${LogSafe.str(reason)})\n",
            )
            if (wait) {
                stops.stopGenerations(listOf(it.generation))
                stoppedRelease.release(it)
            }
        }
        return stopping?.generation
    }

    /** The child, adopted under the launch barrier before daemon shutdown can complete. */
    private fun launch(): McpResult<Process> {
        var spawnFailure: String? = null
        val child = try {
            runtime.launches.launch(spec, runtime.launcher, McpLaunchAdoption(::adopt))
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
        stderr.watch(spec.name, child)
        history.started()
        Executors.defaultThreadFactory().newThread { pump(child) }.apply {
            name = "mcp-host-${spec.name}"
            isDaemon = true
        }.start()
        return McpResult.Served(child)
    }

    private fun adopt(child: Process, owned: McpProcessGeneration): Boolean = synchronized(stateLock) {
        if (closed) {
            false
        } else {
            process = child
            writer = McpWriteTarget(child, child.outputStream.bufferedWriter())
            generation = owned
            true
        }
    }

    private fun completeHandshake(
        process: Process,
        outcome: HostedHandshakeResult,
    ): McpResult<HostedInitialization> = when (outcome) {
        is HostedHandshakeResult.Served -> {
            lastError = null
            log("[mcp-host] ${spec.name}: hosted as pid ${process.pid()}\n")
            McpResult.Served(outcome.initialization)
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
    private suspend fun publish(p: Process, result: JsonObject, budget: Duration): HostedInitialization? {
        val initialized = budget > Duration.ZERO && send.notify(
            codec.notification("notifications/initialized"),
            p,
            budget,
        )
        return synchronized(stateLock) {
            generation?.takeIf { initialized && !closed && process === p && p.isAlive }?.let { owned ->
                initResult = result
                HostedInitialization(result, owned)
            }
        }
    }

    /** The reader thread: one line, one message, until EOF, which fails this child's transport. */
    private fun pump(p: Process) {
        val reader: BufferedReader = p.inputStream.bufferedReader()
        val reason = try {
            generateSequence { reader.readLine() }
                .filter { it.isNotBlank() }
                .mapNotNull(codec::parse)
                .forEach { dispatcher.dispatch(p, it) }
            "stdout closed"
        } catch (e: IOException) {
            "read failed: ${e.message}"
        }
        transportFailures.failed(p, reason)
    }

    /** Fails what is in flight — everything, or only [sessionId]'s requests (its session ended). */
    fun failPending(message: String, sessionId: String? = null) {
        pending.entries
            .filter { sessionId == null || it.value.sessionId == sessionId }
            .map { it.key }
            .forEach { id -> pending.remove(id)?.fail(codec, message) }
    }
}

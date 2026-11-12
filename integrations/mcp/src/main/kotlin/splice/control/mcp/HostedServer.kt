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

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.control.mcp.hosted.HostedClientCalls
import splice.control.mcp.hosted.HostedServerLifecycle
import splice.control.mcp.hosted.HostedServerSpawn
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

internal class HostedServer(
    internal val spec: McpServerSpec,
    private val runtime: HostedServerLifecycle.Runtime,
    internal val codec: JsonRpcCodec,
    internal val log: LogSink,
    private val sink: NotificationSink,
) {
    private val ids = AtomicLong(1)
    internal val pending = ConcurrentHashMap<Long, Pending>()
    internal val reservations = ConcurrentHashMap<HostedClientCalls.RequestId, Pending>()
    val unsettled: List<Long> get() = send.unsettled
    var admission = McpAdmission()
    private val progress = ProgressTokens()
    internal val stops = McpProcessStop()
    private val retention = HostedServerLifecycle.GenerationRetention(stops, runtime.reaped)
    private val stderr = McpStderr(log)
    private val errors = HostedServerSpawn.Error()

    private val history = McpChildHistory(runtime.config.clock, spec.name)

    /** Single-flight spawn: N sessions initializing at once must share ONE child, not race N up
     *  (the 4-session benchmark caught exactly that on 2026-09-13: four copies per server). */
    internal val spawnLock = Mutex()

    /** Guards process/initResult so a pump ending an OLD child can never clear its replacement, and
     *  a spawn that lands after close() tears its child down instead of running orphaned. */
    internal val stateLock = Any()

    @Volatile internal var closed = false

    @Volatile internal var process: Process? = null

    /** The immutable stdin writer for [process]'s exact generation. */
    @Volatile internal var writer: McpWriteTarget? = null

    /** The process tree and cgroup captured at adoption, before its launcher parent can reparent workers. */
    @Volatile internal var generation: McpProcessGeneration? = null

    /** A retired writer's unknown wire charges clear only after its retained generation proves empty. */
    @Volatile internal var retiredWriter: McpWriteTarget? = null

    @Volatile internal var initResult: JsonObject? = null

    internal val tools = ToolsListCache(codec)
    internal val send: McpChildWriter = McpChildWriter(
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
        McpWriteFailure { child, reason -> transportFailures.publish(child, reason) },
    )
    private val transportFailures: HostedServerLifecycle.TransportFailure = HostedServerLifecycle.TransportFailure(
        HostedServerLifecycle.TransportRuntime(spec.name, send, stops, runtime.reaped, log),
        HostedServerLifecycle.TransportActions(
            HostedServerLifecycle.FailureRetirement { child, reason ->
                synchronized(stateLock) {
                    val target = writer
                    if (process !== child || target?.child !== child) {
                        null
                    } else {
                        // A caller wakes from failPending below. Backoff must already know this exact owned
                        // transport failed, while generation release remains separately proven.
                        history.failed()
                        lastError = "$reason; delivery outcome unknown, operation was not replayed"
                        writer = null
                        retiredWriter = target
                        initResult = null
                        tools.drop()
                        HostedServerLifecycle.RetiredChild(target, requireNotNull(generation))
                    }
                }
            },
            HostedServerLifecycle.FailureRelease { child, owned ->
                synchronized(stateLock) {
                    if (process !== child || generation !== owned) {
                        false
                    } else {
                        history.exited()
                        process = null
                        generation = null
                        retiredWriter = null
                        true
                    }
                }
            },
            HostedServerLifecycle.PendingFailure { message -> failPending(message) },
            HostedServerLifecycle.FailureDescription { lastError.orEmpty() },
        ),
    )
    private val clientCalls = HostedClientCalls(
        spec.name,
        HostedClientCalls.Runtime(
            runtime.config,
            codec,
            send,
            HostedClientCalls.Target {
                synchronized(stateLock) {
                    writer?.takeIf { process === it.child && it.child.isAlive && initResult != null }
                }
            },
            log,
        ),
        ids,
        pending,
        reservations,
        progress,
    )
    private val client = HostedClientCalls.Client(this, clientCalls)
    private val handshake = HostedServerSpawn.Handshake(
        spec.name,
        runtime.config,
        codec,
        ids,
        pending,
        send,
        HostedServerSpawn.HandshakePublication(::publish),
    )
    private val handshakeCompletion = HostedServerSpawn.Completion(
        spec.name,
        log,
        errors,
        HostedServerSpawn.HostedSpawnTearDown { reason -> tearDown(reason) },
    )
    internal val stoppedRelease = HostedServerLifecycle.StoppedRelease(
        retention,
        send,
        HostedServerLifecycle.RetiredRelease { stopping ->
            synchronized(stateLock) {
                if (process !== stopping.process || generation !== stopping.generation) {
                    null
                } else {
                    history.exited()
                    process = null
                    generation = null
                    retiredWriter.also { retiredWriter = null }
                }
            }
        },
    )
    private val lifecycle = HostedServerLifecycle(this)

    /** Closes new work on this live generation and atomically proves that no old wire operation remains. */
    internal val replacementFence = HostedServerLifecycle.Replacement(
        send,
        HostedServerLifecycle.ReadyWriteTarget {
            synchronized(stateLock) {
                val child = process
                writer?.takeIf { child === it.child && child.isAlive && initResult != null }
            }
        },
    )
    internal val serverRequests = HostedServerRequests(
        spec.name,
        ServerRequestId(ids::getAndIncrement),
        codec,
        sink,
        send,
        runtime.config.requestTimeout,
    )
    private val dispatcher = HostedServerRequests.Dispatcher(
        HostedServerRequests.DispatchRuntime(codec, send, sink, reservations),
        serverRequests,
        progress,
        pending,
        tools,
        HostedServerRequests.Generation { child ->
            synchronized(stateLock) {
                if (closed || process !== child) false else writer?.child === child
            }
        },
    )
    private val childPump = HostedServerSpawn.ChildPump(codec, dispatcher, transportFailures)
    private val childLaunch = HostedServerSpawn.ChildLauncher(
        spec,
        runtime,
        stderr,
        history,
        errors,
        HostedServerLifecycle.LaunchAdoption(::adopt),
        childPump,
    )
    private val spawner = HostedServerSpawn(
        spec.name,
        history,
        HostedServerSpawn.Readiness {
            val stopping = synchronized(stateLock) {
                val child = process
                val owned = generation
                if (writer != null) {
                    null
                } else {
                    child?.let {
                        owned?.let { generation -> HostedServerLifecycle.StoppingChild(it, generation) }
                    }
                }
            }
            stopping?.let(stoppedRelease::release)
            synchronized(stateLock) { process == null }
        },
        childLaunch,
        HostedServerSpawn.HandshakeResultHandler { child ->
            handshakeCompletion.complete(child, handshake.run(child))
        },
        HostedServerSpawn.HostedSpawnTearDown { reason -> tearDown(reason) },
    )
    private val starter = HostedServerSpawn.Starter(this, spawner)

    var lastError: String?
        get() = errors.value
        internal set(value) {
            errors.value = value
        }

    val alive: Boolean get() = process?.isAlive == true && initResult != null

    /** When the current child was started (epoch ms), or null before the first start. */
    val startedAt: Long? get() = history.startedAt.takeIf { it > 0L }

    /** The launch tuple this process serves; a registered fixed cwd is part of its ownership. */
    val identity: McpIdentity get() = McpIdentity(spec.command, spec.args, spec.env, spec.cwd)
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    /** Holds the generation fence until [enqueue] has accepted this child notification. */
    internal fun enqueueIfOwned(child: Process, enqueue: HostedServerLifecycle.NotificationEnqueue) =
        lifecycle.enqueueIfOwned(child, enqueue)

    internal fun owns(initialization: HostedServerLifecycle.Initialization): Boolean = lifecycle.owns(initialization)

    /** The child's initialize result, spawning and handshaking first when the child is not up. */
    suspend fun ensureStarted(): McpResult<JsonObject> = starter.ensureStarted()

    /** Keeps the exact generation that supplied initialization for registry-bound session publication. */
    suspend fun ensureInitialized(): McpResult<HostedServerLifecycle.Initialization> = starter.ensureInitialized()

    /** Forward one request; the answer comes back under [clientId] no matter how the child numbered it. */
    suspend fun call(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject =
        client.call(sessionId, clientId, request)

    /** Forward a client notification: initialized is the host's own (swallowed), cancelled is remapped
     *  to the host id of THAT session's request and dropped when it names none — a cancel must never
     *  reach the child under another session's id. False when the child could not be written. */
    suspend fun notify(sessionId: String, msg: JsonObject): McpResult<Boolean> =
        client.notify(sessionId, msg)

    /** Permanent: the registry replaced or evicted this server; a spawn racing this call tears down. */
    fun close(reason: String, wait: Boolean = true): McpProcessGeneration? = lifecycle.close(reason, wait)

    /** The registry retries a closed server until this exact root, captured descendants and scope have exited. */
    internal val retirementSettled: Boolean
        get() = lifecycle.retirementSettled

    private fun tearDown(reason: String, wait: Boolean = true): McpProcessGeneration? =
        lifecycle.tearDown(reason, wait)

    private fun adopt(child: Process, owned: McpProcessGeneration): Boolean = lifecycle.adopt(child, owned)

    /** The child is operational only once it has been told `initialized`: send that FIRST, then
     *  publish under stateLock, and only while THIS child is still the adopted, live, unclosed one —
     *  a close() landing between the answer and the publish must win (reviews 2-3, 2026-09-13), or a
     *  session would be minted on a server that is dead, unbound, or not yet serving. */
    private suspend fun publish(
        p: Process,
        result: JsonObject,
        budget: Duration,
    ): HostedServerLifecycle.Initialization? =
        lifecycle.publish(p, result, budget)

    /** Fails what is in flight — everything, or only [sessionId]'s requests (its session ended). */
    fun failPending(message: String, sessionId: String? = null) =
        lifecycle.failPending(message, sessionId)
}

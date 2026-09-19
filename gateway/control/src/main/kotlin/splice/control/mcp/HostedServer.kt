// NEW: v0.4.0 FEATURES.md §8 — one child MCP server process, spoken to over newline-delimited
// JSON-RPC on its stdio, shared by any number of client sessions. The host initializes the child
// ONCE with its own identity; every client session later receives that cached handshake. Request
// ids are remapped per call so two sessions using the same client-side id can never collide, and
// every answer is routed back by the host id it was sent under — never by guesswork.
//
// Server-initiated requests (sampling, elicitation, roots) cannot be multiplexed to one client
// honestly, so the host answers them itself: `ping` -> {}, `roots/list` -> no roots, anything
// else -> method-not-found. A server that needs those is not a sharing candidate; the operator
// excludes it and it keeps launching per session (status quo).
package splice.control.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.launch.McpServerSpec
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val EXIT_WAIT_MS = 1_000L

/** A child that lived shorter than this is a crash, and two in a row are a crash loop. */
private const val CRASH_LOOP_MS = 30_000L

// V4-122: MCP_-prefixed because this is the MCP hosted-server's child-restart backoff, and the
// name BACKOFF_BASE_MS was ALSO carried by UpstreamTransport.kt for the retry curve at 200ms — one
// name over two unrelated budgets, which the checker held as a scar because whoever greps the name
// finds the wrong one and tunes the wrong retry. These are two different policies, so the fix is to
// say WHICH one this is, not to make them agree.
private const val MCP_BACKOFF_BASE_MS = 5_000L
private const val MCP_BACKOFF_MAX_MS = 60_000L
private const val BACKOFF_MAX_SHIFT = 4
private const val MILLIS_PER_SECOND = 1_000L

internal class HostedServer(
    private val spec: McpServerSpec,
    private val config: McpHostConfig,
    private val launcher: McpProcessLauncher,
    private val codec: JsonRpcCodec,
    private val log: LogSink,
    private val sink: NotificationSink,
) {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val progress = ProgressTokens()
    private val stops = McpProcessStop()
    private val stderr = McpStderr(log)

    /** Consecutive short-lived children; the second and later wait before respawning. */
    @Volatile private var crashes = 0

    @Volatile private var exitedAt = 0L
    private val writeLock = Any()

    /** Single-flight spawn: N sessions initializing at once must share ONE child, not race N up
     *  (the 4-session benchmark caught exactly that on 2026-09-13: four copies per server). */
    private val spawnLock = Mutex()

    /** Guards process/initResult so a pump ending an OLD child can never clear its replacement, and
     *  a spawn that lands after close() tears its child down instead of running orphaned. */
    private val stateLock = Any()

    @Volatile private var closed = false

    @Volatile private var process: Process? = null

    @Volatile private var writer: BufferedWriter? = null

    @Volatile private var initResult: JsonObject? = null

    @Volatile var lastError: String? = null
        private set

    @Volatile var startedAt: Long = 0L
        private set

    @Volatile var restarts: Int = 0
        private set

    val alive: Boolean get() = process?.isAlive == true && initResult != null

    /** The launch tuple this process serves; the registry keys reservations by it. */
    val identity: McpIdentity get() = McpIdentity(spec.command, spec.args, spec.env)
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    /** The child's initialize result, spawning and handshaking first when the child is not up. */
    suspend fun ensureStarted(): JsonObject = started ?: spawnLock.withLock {
        if (closed) throw McpHostException("hosted MCP server '${spec.name}' was closed")
        started ?: spawn()
    }

    private val started: JsonObject?
        get() = initResult?.takeIf { process?.isAlive == true }

    /** Forward one request; the answer comes back under [clientId] no matter how the child numbered it. */
    suspend fun call(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
        ensureStarted()
        val hostId = ids.getAndIncrement()
        val (out, token) = progress.outbound(request, hostId)
        val slot = Pending(sessionId, clientId, token)
        pending[hostId] = slot
        if (!send(codec.withId(out, JsonPrimitive(hostId)))) {
            pending.remove(hostId)
            return codec.error(clientId, RPC_SERVER_EXITED, "hosted MCP server '${spec.name}' is not running")
        }
        // withTimeoutOrNull, not withTimeout+catch: an OUTER cancellation (the client went away, a
        // caller's own deadline) is a TimeoutCancellationException too and must propagate, never be
        // read as "the child did not answer" (review 4 regression, 2026-09-13).
        return withTimeoutOrNull(config.requestTimeout) { slot.answer.await() } ?: run {
            pending.remove(hostId)
            codec.error(clientId, RPC_SERVER_EXITED, "hosted MCP server '${spec.name}' did not answer in time")
        }
    }

    /** Forward a client notification: initialized is the host's own (swallowed), cancelled is remapped
     *  to the host id of THAT session's request and dropped when it names none — a cancel must never
     *  reach the child under another session's id. False when the child could not be written. */
    suspend fun notify(sessionId: String, msg: JsonObject): Boolean {
        if (codec.method(msg) == "notifications/initialized") return true
        ensureStarted()
        val out = if (codec.method(msg) != "notifications/cancelled") {
            msg
        } else {
            val clientId = (msg["params"] as? JsonObject)?.get("requestId")
            pending.entries
                .firstOrNull { it.value.sessionId == sessionId && it.value.clientId == clientId }
                ?.let { codec.withCancelledRequestId(msg, JsonPrimitive(it.key)) }
        }
        return out == null || send(out)
    }

    /** Permanent: the registry replaced or evicted this server; a spawn racing this call tears down. */
    fun close(reason: String, wait: Boolean = true): Process? {
        synchronized(stateLock) { closed = true }
        return tearDown(reason, wait)
    }

    private fun tearDown(reason: String, wait: Boolean = true): Process? {
        val p = synchronized(stateLock) {
            process.also {
                process = null
                writer = null
                initResult = null
            }
        }
        failPending("hosted MCP server '${spec.name}' closed: $reason")
        p?.let {
            log("[mcp-host] ${spec.name}: closing pid ${it.pid()} ($reason)\n")
            it.destroy()
            if (wait) stops.await(listOf(it))
        }
        return p
    }

    private suspend fun spawn(): JsonObject {
        if (process != null) failPending("hosted MCP server '${spec.name}' exited")
        // A crash loop (the last child died young, and so did the one before) waits before the next
        // spawn: 5 s, 10 s, ... 60 s; calls in between fail in words instead of respawning at once.
        // One crash still respawns immediately: a single failure is not a loop (review 2026-09-14).
        val loop = if (exitedAt > 0L && exitedAt - startedAt < CRASH_LOOP_MS) crashes + 1 else 0
        val sinceExit = config.clock.millis() - exitedAt
        // Bound the exponent before shifting: bounding the shifted result cannot undo Long overflow.
        val shift = (loop - 2).coerceIn(0, BACKOFF_MAX_SHIFT)
        val wait = if (loop > 1) minOf(MCP_BACKOFF_BASE_MS shl shift, MCP_BACKOFF_MAX_MS) - sinceExit else 0L
        if (wait > 0L) {
            val seconds = wait / MILLIS_PER_SECOND + 1
            val message = "hosted MCP server '${spec.name}' keeps crashing ($loop times); next restart in $seconds s"
            throw McpHostException(message)
        }
        crashes = loop
        if (startedAt > 0L) restarts += 1
        val p = launch()
        stderr.watch(spec.name, p)
        startedAt = config.clock.millis()
        Executors.defaultThreadFactory().newThread { pump(p) }.apply {
            name = "mcp-host-${spec.name}"
            isDaemon = true
        }.start()
        return try {
            handshake(p)
        } catch (e: CancellationException) {
            tearDown("cancelled during the handshake")
            throw e
        }
    }

    /** The child, adopted under [stateLock] — or torn down at once when close() won the race. */
    private fun launch(): Process {
        val p = try {
            launcher(spec)
        } catch (e: IOException) {
            lastError = "spawn failed: ${e.message}"
            throw McpHostException("cannot start '${spec.name}': ${e.message}", e)
        }
        synchronized(stateLock) {
            if (closed) {
                p.destroyForcibly()
                throw McpHostException("hosted MCP server '${spec.name}' was closed while starting")
            }
            process = p
            writer = p.outputStream.bufferedWriter()
        }
        return p
    }

    private suspend fun handshake(p: Process): JsonObject {
        val hostId = ids.getAndIncrement()
        val slot = Pending("host", JsonPrimitive(hostId))
        pending[hostId] = slot
        send(codec.initializeRequest(hostId))
        val answer = withTimeoutOrNull(config.initializeTimeout) { slot.answer.await() }
        if (answer == null) {
            pending.remove(hostId)
            tearDown("no initialize answer")
            throw McpHostException("'${spec.name}' did not complete the MCP handshake")
        }
        val result = answer["result"] as? JsonObject
        if (result == null || !publish(p, result)) {
            tearDown("handshake failed")
            throw McpHostException("'${spec.name}' rejected the MCP handshake (server detail withheld)")
        }
        lastError = null
        log("[mcp-host] ${spec.name}: hosted as pid ${p.pid()}\n")
        return result
    }

    /** The child is operational only once it has been told `initialized`: send that FIRST, then
     *  publish under stateLock, and only while THIS child is still the adopted, live, unclosed one —
     *  a close() landing between the answer and the publish must win (reviews 2-3, 2026-09-13), or a
     *  session would be minted on a server that is dead, unbound, or not yet serving. */
    private fun publish(p: Process, result: JsonObject): Boolean {
        val initialized = send(codec.notification("notifications/initialized"))
        return synchronized(stateLock) {
            (initialized && !closed && process === p && p.isAlive).also { if (it) initResult = result }
        }
    }

    private fun send(msg: JsonObject): Boolean {
        val w = writer ?: return false
        return try {
            synchronized(writeLock) {
                w.write(codec.encode(msg))
                w.newLine()
                w.flush()
            }
            true
        } catch (e: IOException) {
            lastError = "write failed: ${e.message}"
            false
        }
    }

    /** The reader thread: one line, one message, until EOF — which is the child's death. */
    private fun pump(p: Process) {
        val reader: BufferedReader = p.inputStream.bufferedReader()
        try {
            generateSequence { reader.readLine() }
                .filter { it.isNotBlank() }
                .mapNotNull(codec::parse)
                .forEach(::dispatch)
        } catch (e: IOException) {
            lastError = "read failed: ${e.message}"
        }
        if (process !== p) return
        // stdout EOF arrives a beat before the kernel reaps the child; wait that beat so the code is real.
        val code = if (p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) p.exitValue().toString() else "unknown"
        // Compare-and-clear: a replacement may have been spawned during the wait; never clear it.
        val mine = synchronized(stateLock) {
            (process === p).also {
                if (it) {
                    process = null
                    initResult = null
                }
            }
        }
        if (!mine) return
        exitedAt = config.clock.millis()
        lastError = lastError ?: "exited with code $code"
        log("[mcp-host] ${spec.name}: pid ${p.pid()} exited ($lastError)\n")
        failPending("hosted MCP server '${spec.name}' exited")
    }

    private fun dispatch(msg: JsonObject) {
        when (codec.kind(msg)) {
            RpcKind.RESPONSE -> {
                val hostId = JsonScalars.str(msg["id"])?.toLongOrNull()
                val slot = hostId?.let(pending::remove)
                slot?.answer?.complete(codec.withId(msg, slot.clientId))
            }
            RpcKind.REQUEST -> send(codec.serverReply(msg))
            RpcKind.NOTIFICATION -> if (codec.method(msg) == "notifications/progress") {
                // A missing owner is late or unknown progress, never a global notification.
                progress.owner(msg, pending)?.let { (slot, routed) -> sink.onProgress(slot.sessionId, routed) }
            } else {
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

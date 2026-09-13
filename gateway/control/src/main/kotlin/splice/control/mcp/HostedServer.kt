// NEW (v0.4.0, FEATURES.md §8): one child MCP server process, spoken to over newline-delimited
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.launch.McpServerSpec
import splice.core.util.LogSink
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val RPC_SERVER_EXITED = -32000
private const val RPC_METHOD_NOT_FOUND = -32601
private const val HOST_PROTOCOL = "2025-11-25"
private const val EXIT_WAIT_MS = 1_000L
private const val DESTROY_GRACE_MS = 2_000L

/** Spawns the child; a seam so tests can run a scripted server and the host never hard-codes Java's launcher. */
public fun interface McpProcessLauncher {
    public operator fun invoke(spec: McpServerSpec): Process
}

/** The default launcher: the spec's command/args/env, stderr discarded (MCP servers log there freely). */
public class StdioProcessLauncher : McpProcessLauncher {
    override fun invoke(spec: McpServerSpec): Process {
        val builder = ProcessBuilder(listOf(spec.command) + spec.args)
        builder.environment().putAll(spec.env)
        builder.redirectError(Redirect.DISCARD)
        return builder.start()
    }
}

/** A forwarded request waiting for the child: who asked, under which client id. */
private class Pending(val sessionId: String, val clientId: JsonElement) {
    val answer = CompletableDeferred<JsonObject>()

    /** The answer the caller gets when the child never will answer: an error under ITS id. */
    fun fail(codec: JsonRpcCodec, message: String) {
        answer.complete(codec.error(clientId, RPC_SERVER_EXITED, message))
    }
}

/** Where the child's unsolicited notifications go: the host fans them out to every session. */
internal fun interface NotificationSink {
    fun onNotification(msg: JsonObject)
}

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
        val slot = Pending(sessionId, clientId)
        pending[hostId] = slot
        if (!send(codec.withId(request, JsonPrimitive(hostId)))) {
            pending.remove(hostId)
            return codec.error(clientId, RPC_SERVER_EXITED, "hosted MCP server '${spec.name}' is not running")
        }
        return try {
            withTimeout(config.requestTimeout) { slot.answer.await() }
        } catch (_: TimeoutCancellationException) {
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
    fun close(reason: String) {
        synchronized(stateLock) { closed = true }
        tearDown(reason)
    }

    private fun tearDown(reason: String) {
        val p = synchronized(stateLock) {
            process.also {
                process = null
                initResult = null
            }
        } ?: return
        log("[mcp-host] ${spec.name}: closing pid ${p.pid()} ($reason)\n")
        p.destroy()
        if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        failPending("hosted MCP server '${spec.name}' closed: $reason")
    }

    private suspend fun spawn(): JsonObject {
        if (process != null) failPending("hosted MCP server '${spec.name}' exited")
        if (startedAt > 0L) restarts += 1
        val p = launch()
        startedAt = config.clock.millis()
        Thread({ pump(p) }, "mcp-host-${spec.name}").apply { isDaemon = true }.start()
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
        val params = buildJsonObject {
            put("protocolVersion", HOST_PROTOCOL)
            // No roots: roots are per client and the host serves many; a server that needs them is
            // not shared (McpSharing rejects project-scoped entries) rather than given an empty list.
            put("capabilities", buildJsonObject {})
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", "splice-mcp-host")
                    put("version", "0.4.0")
                },
            )
        }
        send(codec.request(JsonPrimitive(hostId), "initialize", params))
        val answer = try {
            withTimeout(config.initializeTimeout) { slot.answer.await() }
        } catch (_: TimeoutCancellationException) {
            pending.remove(hostId)
            tearDown("no initialize answer")
            throw McpHostException("'${spec.name}' did not complete the MCP handshake")
        }
        val result = answer["result"] as? JsonObject
        if (result == null || !publish(p, result)) {
            tearDown("handshake failed")
            throw McpHostException("'${spec.name}' rejected the MCP handshake: ${answer["error"]}")
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
        lastError = lastError ?: "exited with code $code"
        log("[mcp-host] ${spec.name}: pid ${p.pid()} exited ($lastError)\n")
        failPending("hosted MCP server '${spec.name}' exited")
    }

    private fun dispatch(msg: JsonObject) {
        when (codec.kind(msg)) {
            RpcKind.RESPONSE -> {
                val hostId = (msg["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                val slot = hostId?.let(pending::remove)
                slot?.answer?.complete(codec.withId(msg, slot.clientId))
            }
            RpcKind.REQUEST -> answerServerRequest(msg)
            RpcKind.NOTIFICATION -> sink.onNotification(msg)
            RpcKind.INVALID -> Unit
        }
    }

    private fun answerServerRequest(msg: JsonObject) {
        val id = msg.getValue("id")
        val reply = when (codec.method(msg)) {
            "ping" -> codec.result(id, buildJsonObject {})
            "roots/list" -> codec.result(id, buildJsonObject { put("roots", buildJsonArray {}) })
            else -> codec.error(id, RPC_METHOD_NOT_FOUND, "splice-mcp-host does not proxy server-initiated requests")
        }
        send(reply)
    }

    /** Fails what is in flight — everything, or only [sessionId]'s requests (its session ended). */
    fun failPending(message: String, sessionId: String? = null) {
        pending.entries
            .filter { sessionId == null || it.value.sessionId == sessionId }
            .map { it.key }
            .forEach { id -> pending.remove(id)?.fail(codec, message) }
    }
}

public class McpHostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

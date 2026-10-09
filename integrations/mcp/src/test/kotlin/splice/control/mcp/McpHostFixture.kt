package splice.control.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val MCP_HOST_INIT =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
internal const val MCP_HOST_LIST = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""
internal const val MCP_HOST_STREAM_WAIT_MS = 5_000L

/** One pending [McpHostFixture.awaitLogged]: [offer] completes it on the first text carrying its
 *  fragment — the log sink offers each line, the caller offers the log it already holds — and
 *  [await] is that completion, bounded. */
private class LogWaiter(private val fragment: String) {
    private val done = CompletableDeferred<Unit>()

    fun offer(text: String) {
        if (text.contains(fragment)) done.complete(Unit)
    }

    suspend fun await(timeoutMs: Long) = withTimeout(timeoutMs) { done.await() }
}

class McpFakeClock(var now: Long = 1_000_000L) : HostClock {
    override fun millis() = now
}

abstract class McpHostFixture {

    protected val json = Json { ignoreUnknownKeys = true }
    protected val clock = McpFakeClock()
    protected val log = StringBuilder()
    private val logWaiters = CopyOnWriteArrayList<LogWaiter>()
    protected lateinit var host: McpHost

    /** V4-139: a line the host logs, awaited as the EVENT of its being logged: the fixture's sink
     *  completes every waiter whose fragment the line carries. Registered before the log is
     *  checked, so a line that lands in between is caught by one or the other. */
    protected suspend fun awaitLogged(fragment: String, timeoutMs: Long = MCP_HOST_STREAM_WAIT_MS) {
        val waiter = LogWaiter(fragment)
        logWaiters += waiter
        try {
            waiter.offer(synchronized(log) { log.toString() })
            waiter.await(timeoutMs)
        } finally {
            logWaiters -= waiter
        }
    }

    /** V4-139: a file the fake child creates, awaited on the filesystem's own creation event
     *  (WatchService, inotify on Linux) with a deadline — never a poll of the clock. Registered
     *  before the existence check, so a file created in between is seen by one or the other. */
    protected fun awaitFile(path: Path, timeoutMs: Long = MCP_HOST_STREAM_WAIT_MS) {
        val dir = checkNotNull(path.parent) { "$path has no parent to watch" }
        dir.fileSystem.newWatchService().use { watch ->
            dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!Files.exists(path)) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "$path was never created within ${timeoutMs}ms" }
                watch.poll(left, TimeUnit.NANOSECONDS)?.let { key ->
                    key.pollEvents()
                    key.reset()
                }
            }
        }
    }

    protected fun boot(maxServers: Int = 32, requestTimeout: Duration = 20.seconds): McpHost {
        val global = buildJsonObject {
            put("fake", FakeMcpServer.entry())
            put("alias", FakeMcpServer.entry())
            put("fake2", FakeMcpServer.entry(mapOf("FAKE2" to "1")))
            put("fake3", FakeMcpServer.entry(mapOf("FAKE2" to "2")))
            put("remote", json.parseToJsonElement("""{"type":"http","url":"https://x/mcp"}"""))
        }
        val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "k" }, DirectoryProbe { false })
        host = McpHost(
            sharing,
            { global },
            McpHostConfig(idleTimeout = 30.minutes, maxServers = maxServers, requestTimeout = requestTimeout, clock = clock),
            log = LogSink { line ->
                synchronized(log) { log.append(line) }
                logWaiters.forEach { it.offer(line) }
            },
        )
        return host
    }

    @AfterEach
    fun tearDown() {
        if (::host.isInitialized) host.stop()
    }

    protected suspend fun init(name: String = "fake"): String {
        val reply = host.post(name, null, MCP_HOST_INIT)
        assertEquals(200, reply.status, reply.body)
        val obj = json.parseToJsonElement(reply.body!!).jsonObject
        // The CHILD's negotiated version, never the client's requested one (review 2026-09-13).
        assertEquals("2025-11-25", obj["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("1", obj["id"]!!.jsonPrimitive.content)
        return checkNotNull(reply.sessionId)
    }

    protected suspend fun call(
        session: String,
        id: Int,
        op: String,
        text: String = "",
        name: String = "fake",
    ): JsonObject = sendCall(session, id.toString(), op, text, name)

    protected suspend fun call(
        session: String,
        id: String,
        op: String,
        text: String = "",
        name: String = "fake",
    ): JsonObject = sendCall(session, "\"$id\"", op, text, name)

    private suspend fun sendCall(session: String, idJson: String, op: String, text: String, name: String): JsonObject {
        val body = """{"jsonrpc":"2.0","id":$idJson,"method":"tools/call",""" +
            """"params":{"name":"echo","arguments":{"op":"$op","text":"$text"}}}"""
        val reply = host.post(name, session, body)
        assertEquals(200, reply.status, reply.body)
        return json.parseToJsonElement(reply.body!!).jsonObject
    }

    protected fun text(answer: JsonObject): String =
        (answer["result"]!!.jsonObject["content"] as JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content

    protected fun status(name: String): JsonObject =
        json.parseToJsonElement(host.statusJson()).jsonObject["servers"]!!.jsonObject[name]!!.jsonObject

    protected fun hosted(name: String): Boolean = status(name)["hosted"]!!.jsonPrimitive.content.toBoolean()
}

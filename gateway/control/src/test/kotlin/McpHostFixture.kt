import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import splice.control.mcp.HostClock
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.core.launch.DirectoryProbe
import splice.core.launch.McpSharing
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A scripted stdio MCP server: answers initialize, echoes tools/call with its own pid, emits one
 *  notification on `notify`, pings the client on `ping-me`, and dies on `crash`. */
private const val FAKE_SERVER = """
import json, os, signal, sys, time
if os.environ.get("FAKE2"):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
def send(o):
    sys.stdout.write(json.dumps(o) + "\n"); sys.stdout.flush()
for line in sys.stdin:
    line = line.strip()
    if not line: continue
    m = json.loads(line)
    method = m.get("method"); rid = m.get("id")
    if method == "notifications/cancelled":
        send({"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"cancelled=%s" % m["params"].get("requestId")}})
        continue
    if method == "initialize":
        send({"jsonrpc":"2.0","id":rid,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
              "serverInfo":{"name":"fake","version":"1"}}})
    elif method == "tools/list":
        send({"jsonrpc":"2.0","id":rid,"result":{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}})
    elif method == "tools/call":
        args = m["params"].get("arguments", {})
        if args.get("op") == "crash":
            os._exit(3)
        if args.get("op") == "hold":
            with open(args["text"], "w") as ready: ready.write("started")
            time.sleep(1)
        if args.get("op") == "slow":
            time.sleep(float(args.get("seconds", 1)))
        if args.get("op") == "timeout-progress":
            time.sleep(0.5)
        if args.get("op") == "stderr":
            sys.stderr.write("synthetic-private-stderr " * 10000); sys.stderr.flush()
        if args.get("op") == "notify":
            send({"jsonrpc":"2.0","method":"notifications/tools/list_changed"})
        if args.get("op") == "overflow":
            for i in range(257):
                send({"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"test://resource"}})
        if args.get("op") == "progress":
            tok = m["params"].get("_meta", {}).get("progressToken")
            send({"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":tok,"progress":1}})
        if args.get("op") == "ping-me":
            send({"jsonrpc":"2.0","id":"srv-1","method":"ping"})
        send({"jsonrpc":"2.0","id":rid,"result":{"content":[{"type":"text","text":"pid=%d echo=%s" % (os.getpid(), args.get("text",""))}]}})
        if args.get("op") in ("late-progress", "timeout-progress"):
            tok = m["params"].get("_meta", {}).get("progressToken")
            send({"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":tok,"progress":2}})
"""

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

    protected fun boot(dir: Path, maxServers: Int = 32, requestTimeout: Duration = 20.seconds): McpHost {
        val script = dir.resolve("fake_mcp.py")
        script.writeText(FAKE_SERVER)
        val global = json.parseToJsonElement(
            """{"fake":{"command":"python3","args":["$script"]},
                "alias":{"command":"python3","args":["$script"]},
                "fake2":{"command":"python3","args":["$script"],"env":{"FAKE2":"1"}},
                "fake3":{"command":"python3","args":["$script"],"env":{"FAKE2":"2"}},
                "remote":{"type":"http","url":"https://x/mcp"}}""",
        ).jsonObject
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

    protected suspend fun call(session: String, id: Any, op: String, text: String = "", name: String = "fake"): JsonObject {
        val idJson = if (id is String) "\"$id\"" else id.toString()
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

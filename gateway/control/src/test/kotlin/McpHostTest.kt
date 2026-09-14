import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.mcp.HostClock
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.core.launch.DirectoryProbe
import splice.core.launch.McpSharing
import splice.core.util.LogSink
import java.nio.file.Path
import kotlin.io.path.writeText
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
        if args.get("op") == "slow":
            time.sleep(float(args.get("seconds", 1)))
        if args.get("op") == "notify":
            send({"jsonrpc":"2.0","method":"notifications/tools/list_changed"})
        if args.get("op") == "progress":
            tok = m["params"].get("_meta", {}).get("progressToken")
            send({"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":tok,"progress":1}})
        if args.get("op") == "ping-me":
            send({"jsonrpc":"2.0","id":"srv-1","method":"ping"})
        send({"jsonrpc":"2.0","id":rid,"result":{"content":[{"type":"text","text":"pid=%d echo=%s" % (os.getpid(), args.get("text",""))}]}})
"""

private const val INIT =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
private const val LIST = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""
private const val STREAM_WAIT_MS = 5_000L

private class FakeClock(var now: Long = 1_000_000L) : HostClock {
    override fun millis() = now
}

class McpHostTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val clock = FakeClock()
    private val log = StringBuilder()
    private lateinit var host: McpHost

    private fun boot(dir: Path, maxServers: Int = 32): McpHost {
        val script = dir.resolve("fake_mcp.py")
        script.writeText(FAKE_SERVER)
        val global = json.parseToJsonElement(
            """{"fake":{"command":"python3","args":["$script"]},
                "alias":{"command":"python3","args":["$script"]},
                "fake2":{"command":"python3","args":["$script"],"env":{"FAKE2":"1"}},
                "remote":{"type":"http","url":"https://x/mcp"}}""",
        ).jsonObject
        val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "k" }, DirectoryProbe { false })
        host = McpHost(
            sharing,
            { global },
            McpHostConfig(idleTimeout = 30.minutes, maxServers = maxServers, requestTimeout = 20.seconds, clock = clock),
            log = LogSink { log.append(it) },
        )
        return host
    }

    @AfterEach
    fun tearDown() {
        if (::host.isInitialized) host.stop()
    }

    private suspend fun init(name: String = "fake"): String {
        val reply = host.post(name, null, INIT)
        assertEquals(200, reply.status, reply.body)
        val obj = json.parseToJsonElement(reply.body!!).jsonObject
        // The CHILD's negotiated version, never the client's requested one (review 2026-09-13).
        assertEquals("2025-11-25", obj["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("1", obj["id"]!!.jsonPrimitive.content)
        return checkNotNull(reply.sessionId)
    }

    private suspend fun call(session: String, id: Any, op: String, text: String = "", name: String = "fake"): JsonObject {
        val idJson = if (id is String) "\"$id\"" else id.toString()
        val body = """{"jsonrpc":"2.0","id":$idJson,"method":"tools/call",""" +
            """"params":{"name":"echo","arguments":{"op":"$op","text":"$text"}}}"""
        val reply = host.post(name, session, body)
        assertEquals(200, reply.status, reply.body)
        return json.parseToJsonElement(reply.body!!).jsonObject
    }

    private fun text(answer: JsonObject): String =
        (answer["result"]!!.jsonObject["content"] as JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun status(name: String): JsonObject =
        json.parseToJsonElement(host.statusJson()).jsonObject["servers"]!!.jsonObject[name]!!.jsonObject

    private fun hosted(name: String): Boolean = status(name)["hosted"]!!.jsonPrimitive.content.toBoolean()

    @Test
    fun `two sessions share one process and each gets its own id back`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val a = init()
        val b = init()
        assertNotEquals(a, b)
        val (ra, rb) = listOf(async { call(a, 7, "echo", "A") }, async { call(b, 7, "echo", "B") }).awaitAll()
        assertEquals("7", ra["id"]!!.jsonPrimitive.content)
        assertEquals("7", rb["id"]!!.jsonPrimitive.content)
        assertTrue(text(ra).endsWith("echo=A"), text(ra))
        assertTrue(text(rb).endsWith("echo=B"), text(rb))
        assertEquals(text(ra).substringBefore(" "), text(rb).substringBefore(" "), "same pid for both sessions")
        assertEquals(2, status("fake")["sessions"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", status("remote")["eligible"]!!.jsonPrimitive.content)
    }

    @Test
    fun `concurrent initializes from many sessions spawn exactly one process`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val sessions = (1..6).map { async { init() } }.awaitAll()
        val pids = sessions.mapIndexed { i, s -> text(call(s, i, "echo", "x")).substringBefore(" ") }.toSet()
        assertEquals(1, pids.size, "one child for six sessions, got $pids")
        assertEquals(0, status("fake")["restarts"]!!.jsonPrimitive.content.toInt())
        assertEquals(6, status("fake")["sessions"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `aliases with an identical launch tuple share one process and a different tuple does not`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init("fake")
            val b = init("alias")
            val c = init("fake2")
            val pidA = text(call(a, 1, "echo", "a")).substringBefore(" ")
            val pidB = text(call(b, 1, "echo", "b", name = "alias")).substringBefore(" ")
            val pidC = text(call(c, 1, "echo", "c", name = "fake2")).substringBefore(" ")
            assertEquals(pidA, pidB, "one process for two names with the same tuple")
            assertNotEquals(pidA, pidC, "a different env is a different process")
            assertEquals(status("fake")["pid"], status("alias")["pid"])
            val ids = (status("fake")["session_ids"] as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(listOf(a), ids)
        }

    @Test
    fun `a later request naming another protocol version is refused, absence rides the negotiated one`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val s = init()
        assertEquals(400, host.post("fake", s, LIST, protocolVersion = "2025-03-26").status)
        assertEquals(200, host.post("fake", s, LIST, protocolVersion = "2025-11-25").status)
        assertEquals(200, host.post("fake", s, LIST).status)
        assertFalse(host.protocolAccepted("fake", s, "2024-11-05"))
        // An ended session is 404 whatever version the client still names: reinitialize, not 400.
        assertTrue(host.endSession("fake", s))
        assertEquals(404, host.post("fake", s, LIST, protocolVersion = "2025-11-25").status)
        assertEquals(404, host.post("fake", "unknown", LIST, protocolVersion = "1999-01-01").status)
    }

    @Test
    fun `a cancel is remapped to that session's own request and dropped when it names none`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init()
            val b = init()
            val streamB = checkNotNull(host.openStream("fake", b))
            val streamA = checkNotNull(host.openStream("fake", a))
            val slow = async { call(a, 7, "slow", "1") }
            kotlinx.coroutines.delay(300)
            val cancel = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}"""
            // B has no request 7: the cancel must not reach the child under A's host id.
            assertEquals(202, host.post("fake", b, cancel).status)
            val silent = kotlinx.coroutines.withTimeoutOrNull(500) { streamB.receive() }
            assertNull(silent, "a cancel naming no request of its own session is dropped")
            // A's cancel IS forwarded, under the host id (not 7) the child knows.
            assertEquals(202, host.post("fake", a, cancel).status)
            val seen = withTimeout(STREAM_WAIT_MS) { streamA.receive() }
            assertTrue(seen.contains("cancelled=") && !seen.contains("cancelled=7"), seen)
            slow.await()
            host.closeStream("fake", a)
            host.closeStream("fake", b)
        }

    @Test
    fun `status says hosting is off when the planner is disabled even with no servers`() {
        val off = McpSharing(false, emptySet(), "http://127.0.0.1:1/mcp/", { "k" }, DirectoryProbe { false })
        val status = McpHost(
            off,
            { JsonObject(emptyMap()) },
            McpHostConfig(clock = clock),
            log = LogSink { },
        ).statusJson()
        assertEquals("false", json.parseToJsonElement(status).jsonObject["hosting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `string ids and concurrent calls never cross sessions`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val a = init()
        val b = init()
        val answers = (1..6).map { i ->
            async { call(if (i % 2 == 0) a else b, "req-$i", if (i == 3) "slow" else "echo", "n$i") }
        }.awaitAll()
        answers.forEachIndexed { idx, ans ->
            assertEquals("req-${idx + 1}", ans["id"]!!.jsonPrimitive.content)
            assertTrue(text(ans).endsWith("echo=n${idx + 1}"), text(ans))
        }
    }

    @Test
    fun `a cancelled initialize releases its reservation so capacity is not held by a ghost`(@TempDir dir: Path) =
        runBlocking {
            boot(dir, maxServers = 1)
            // 1 ms is far less than a python child needs to answer initialize: the handshake is
            // cancelled with the server still reserved — and the finally must hand that back.
            assertNull(withTimeoutOrNull(1) { host.post("fake", null, INIT) })
            val s = init("fake2")
            assertEquals(200, host.post("fake2", s, LIST).status)
        }

    @Test
    fun `an unknown session is 404 and the initialized notification is 202`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val s = init()
        assertEquals(404, host.post("fake", "nope", LIST).status)
        assertEquals(202, host.post("fake", s, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        assertEquals(400, host.post("fake", s, "not json").status)
        assertEquals(503, host.post("remote", null, INIT).status, "an unhosted name cannot initialize")
    }

    @Test
    fun `notifications fan out to every session's stream and server pings are answered by the host`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val a = init()
        val b = init()
        val sa = checkNotNull(host.openStream("fake", a))
        val sb = checkNotNull(host.openStream("fake", b))
        call(a, 1, "notify")
        call(b, 2, "ping-me")
        val fa = withTimeout(STREAM_WAIT_MS) { sa.receive() }
        val fb = withTimeout(STREAM_WAIT_MS) { sb.receive() }
        assertTrue(fa.contains("notifications/tools/list_changed"), fa)
        assertTrue(fb.contains("notifications/tools/list_changed"), fb)
        assertNull(host.openStream("fake", "nope"))
    }

    @Test
    fun `a progress notification reaches only the session whose request carries its token`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init()
            val b = init()
            val sa = checkNotNull(host.openStream("fake", a))
            val sb = checkNotNull(host.openStream("fake", b))
            val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"echo",""" +
                """"_meta":{"progressToken":"t-1"},"arguments":{"op":"progress"}}}"""
            assertEquals(200, host.post("fake", a, body).status)
            val fa = withTimeout(STREAM_WAIT_MS) { sa.receive() }
            assertTrue(fa.contains("notifications/progress") && fa.contains("\"t-1\""), "a's own token back: $fa")
            call(b, 2, "notify")
            val fb = withTimeout(STREAM_WAIT_MS) { sb.receive() }
            assertTrue(fb.contains("list_changed"), "b saw no progress frame before its own notification: $fb")
        }

    @Test
    fun `a second crash in a row waits before respawning, and calls in between fail in words`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init()
            val crash = """{"jsonrpc":"2.0","id":9,"method":"tools/call",""" +
                """"params":{"name":"echo","arguments":{"op":"crash"}}}"""
            host.post("fake", a, crash)
            call(a, 1, "echo", "x") // one crash: respawned at once
            host.post("fake", a, crash)
            val refused = json.parseToJsonElement(host.post("fake", a, LIST).body!!).jsonObject
            val message = refused["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertTrue(message.contains("keeps crashing"), message)
            clock.now += 6_000L
            assertTrue(text(call(a, 2, "echo", "y")).endsWith("echo=y"), "respawned once the backoff passed")
        }

    @Test
    fun `closing a child that ignores TERM never holds the registry lock`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val stubborn = init("fake2")
        call(stubborn, 1, "echo", "x", name = "fake2")
        clock.now += 60.minutes.inWholeMilliseconds
        val sweeper = Thread { host.sweep() }.apply { start() }
        Thread.sleep(200)
        val started = System.nanoTime()
        host.statusJson()
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        sweeper.join()
        assertTrue(waitedMs < 1_000, "status waited ${waitedMs}ms behind the 2 s teardown grace")
        assertFalse(hosted("fake2"))
    }

    @Test
    fun `a crash fails the pending call in words and the next call respawns`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val a = init()
        val b = init()
        val first = text(call(a, 1, "echo", "x")).substringBefore(" ")
        val crashed = host.post(
            "fake",
            a,
            """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"echo","arguments":{"op":"crash"}}}""",
        )
        assertEquals(200, crashed.status)
        val err = json.parseToJsonElement(crashed.body!!).jsonObject
        assertEquals("9", err["id"]!!.jsonPrimitive.content)
        assertTrue(err["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("exited"), crashed.body)
        val again = text(call(b, 2, "echo", "y")).substringBefore(" ")
        assertNotEquals(first, again, "respawned under a new pid")
        assertTrue(status("fake")["restarts"]!!.jsonPrimitive.content.toInt() >= 1)
    }

    @Test
    fun `ending one session keeps the other's access`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val a = init()
        val b = init()
        assertTrue(host.endSession("fake", a))
        assertFalse(host.endSession("fake", a))
        assertEquals(404, host.post("fake", a, LIST).status)
        assertTrue(text(call(b, 4, "echo", "still")).endsWith("echo=still"))
    }

    @Test
    fun `idle servers are reaped only when no session streams or speaks within the timeout`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init()
            call(a, 1, "echo", "x")
            clock.now += 10.minutes.inWholeMilliseconds
            host.sweep()
            assertTrue(hosted("fake"), "still busy within the idle window")
            val stream = checkNotNull(host.openStream("fake", a))
            clock.now += 60.minutes.inWholeMilliseconds
            host.sweep()
            assertTrue(hosted("fake"), "an open stream keeps the server")
            host.closeStream("fake", a)
            stream.cancel()
            clock.now += 60.minutes.inWholeMilliseconds
            host.sweep()
            assertFalse(hosted("fake"), "reaped once idle with no stream")
            assertEquals(404, host.post("fake", a, LIST).status)
        }

    @Test
    fun `a server whose last session ended is idle from then, so the next session reuses it`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init()
            val pid = text(call(a, 1, "echo", "x")).substringBefore(" ")
            assertTrue(host.endSession("fake", a))
            clock.now += 10.minutes.inWholeMilliseconds
            host.sweep()
            assertTrue(hosted("fake"), "no session, but the last one ended inside the idle window")
            val b = init()
            assertEquals(pid, text(call(b, 2, "echo", "y")).substringBefore(" "), "the same process serves")
            assertTrue(host.endSession("fake", b))
            clock.now += 60.minutes.inWholeMilliseconds
            host.sweep()
            assertFalse(hosted("fake"), "reaped once the idle window has passed since the last session")
        }

    @Test
    fun `at capacity the longest-idle streamless server is evicted for the newcomer`(@TempDir dir: Path) = runBlocking {
        boot(dir, maxServers = 1)
        val a = init("fake")
        clock.now += 1.minutes.inWholeMilliseconds
        val b = init("fake2")
        assertFalse(hosted("fake"), "evicted")
        assertTrue(hosted("fake2"))
        assertEquals(404, host.post("fake", a, LIST).status)
        assertTrue(text(call(b, 1, "echo", "n", name = "fake2")).endsWith("echo=n"))
        assertTrue(log.contains("evicted"), log.toString())
    }
}

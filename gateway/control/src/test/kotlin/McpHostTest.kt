import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import json, os, sys, time
def send(o):
    sys.stdout.write(json.dumps(o) + "\n"); sys.stdout.flush()
for line in sys.stdin:
    line = line.strip()
    if not line: continue
    m = json.loads(line)
    method = m.get("method"); rid = m.get("id")
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
                "fake2":{"command":"python3","args":["$script"]},
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
        assertEquals("2025-06-18", obj["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
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

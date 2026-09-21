package splice.control.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import splice.core.util.LogSink
import java.nio.file.Path

class McpHostTest : McpHostFixture() {

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
        assertEquals(400, host.post("fake", s, MCP_HOST_LIST, protocolVersion = "2025-03-26").status)
        assertEquals(200, host.post("fake", s, MCP_HOST_LIST, protocolVersion = "2025-11-25").status)
        assertEquals(200, host.post("fake", s, MCP_HOST_LIST).status)
        assertFalse(host.protocolAccepted("fake", s, "2024-11-05"))
        // An ended session is 404 whatever version the client still names: reinitialize, not 400.
        assertTrue(host.endSession("fake", s))
        assertEquals(404, host.post("fake", s, MCP_HOST_LIST, protocolVersion = "2025-11-25").status)
        // V4-148: an id the host never knew is adopted, and an adopted session takes the version the
        // client negotiated with the child that ran before — a 400 here is the same break as the 404.
        assertEquals(200, host.post("fake", "unknown", MCP_HOST_LIST, protocolVersion = "1999-01-01").status)
    }

    @Test
    fun `a cancel is remapped to that session's own request and dropped when it names none`(@TempDir dir: Path) =
        runBlocking {
            boot(dir)
            val a = init()
            val b = init()
            val streamB = checkNotNull(host.openStream("fake", b))
            val streamA = checkNotNull(host.openStream("fake", a))
            // The child's hold op writes its ready file on RECEIVING request 7 and then stays in
            // flight for 1 s: the file's creation is the event that 7 is live at the child, so the
            // cancels below are timed from it rather than from a 300 ms guess (V4-139).
            val ready = dir.resolve("request-7-live")
            val slow = async(Dispatchers.IO) { call(a, 7, "hold", ready.toString()) }
            awaitFile(ready)
            val cancel = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}"""
            // B has no request 7: the cancel must not reach the child under A's host id.
            assertEquals(202, host.post("fake", b, cancel).status)
            val silent = kotlinx.coroutines.withTimeoutOrNull(500) { streamB.receive() }
            assertNull(silent, "a cancel naming no request of its own session is dropped")
            // A's cancel IS forwarded, under the host id (not 7) the child knows.
            assertEquals(202, host.post("fake", a, cancel).status)
            val seen = withTimeout(MCP_HOST_STREAM_WAIT_MS) { streamA.receive() }
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
            assertNull(withTimeoutOrNull(1) { host.post("fake", null, MCP_HOST_INIT) })
            val s = init("fake2")
            assertEquals(200, host.post("fake2", s, MCP_HOST_LIST).status)
        }

    @Test
    fun `an unknown session is adopted, a notification on one is 404, the initialized one is 202`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val s = init()
        // V4-148: a REQUEST on an id this host does not know is served by adopting the id, because the
        // spec's "session not found" is a signal Claude Code ignores; a NOTIFICATION needs no session.
        assertEquals(200, host.post("fake", "nope", MCP_HOST_LIST).status)
        assertEquals(
            404,
            host.post("fake", "nope-2", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status,
        )
        assertEquals(202, host.post("fake", s, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        assertEquals(400, host.post("fake", s, "not json").status)
        assertEquals(503, host.post("remote", null, MCP_HOST_INIT).status, "an unhosted name cannot initialize")
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
        val fa = withTimeout(MCP_HOST_STREAM_WAIT_MS) { sa.receive() }
        val fb = withTimeout(MCP_HOST_STREAM_WAIT_MS) { sb.receive() }
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
            val fa = withTimeout(MCP_HOST_STREAM_WAIT_MS) { sa.receive() }
            assertTrue(fa.contains("notifications/progress") && fa.contains("\"t-1\""), "a's own token back: $fa")
            call(b, 2, "notify")
            val fb = withTimeout(MCP_HOST_STREAM_WAIT_MS) { sb.receive() }
            assertTrue(fb.contains("list_changed"), "b saw no progress frame before its own notification: $fb")
        }
}

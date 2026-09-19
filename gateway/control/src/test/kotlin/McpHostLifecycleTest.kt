import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class McpHostLifecycleTest : McpHostFixture() {

    @Test
    fun `progress after response or timeout never becomes a broadcast`(@TempDir dir: Path) = runBlocking {
        boot(dir, requestTimeout = 300.milliseconds)
        val a = init()
        val b = init()
        val stream = checkNotNull(host.openStream("fake", b))
        for (op in listOf("late-progress", "timeout-progress")) {
            val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"echo",""" +
                """"_meta":{"progressToken":"private-token"},"arguments":{"op":"$op"}}}"""
            host.post("fake", a, body)
            call(b, 9, "notify")
            val frame = withTimeout(MCP_HOST_STREAM_WAIT_MS) { stream.receive() }
            assertTrue(frame.contains("list_changed"), "orphaned progress escaped its owner: $frame")
        }
    }

    @Test
    fun `a streamless active operation survives idle sweep and refuses capacity eviction`(@TempDir dir: Path) =
        runBlocking {
            boot(dir, maxServers = 1)
            val a = init()
            val ready = dir.resolve("operation-started")
            val operation = async(Dispatchers.IO) { call(a, 8, "hold", ready.toString()) }
            awaitFile(ready)
            clock.now += 60.minutes.inWholeMilliseconds
            host.sweep()
            assertTrue(hosted("fake"), "active streamless call was swept")
            assertEquals(503, host.post("fake2", null, MCP_HOST_INIT).status, "active call must not be evicted")
            assertTrue(text(operation.await()).contains("echo="))
        }

    @Test
    fun `overflow forces reinitialize with or without GET and closing its pump releases capacity`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir, maxServers = 1)
        for (streaming in listOf(false, true)) {
            val session = init()
            val stream = if (streaming) checkNotNull(host.openStream("fake", session)) else null
            assertEquals(if (streaming) 1 else 0, status("fake")["streams"]!!.jsonPrimitive.content.toInt())
            call(session, 8, "overflow")
            assertEquals(404, host.post("fake", session, MCP_HOST_LIST).status)
            if (stream != null) {
                assertTrue(withTimeout(MCP_HOST_STREAM_WAIT_MS) { stream.receive() }.contains("reinitialize"))
                assertTrue(withTimeout(MCP_HOST_STREAM_WAIT_MS) { stream.receiveCatching() }.isClosed)
                host.closeStream("fake", session)
            }
            assertEquals(0, status("fake")["streams"]!!.jsonPrimitive.content.toInt())
        }
        init("fake2")
        assertFalse(hosted("fake"), "a closed overflowed pump cannot prevent capacity eviction")
    }

    @Test
    fun `stopped host cannot initialize a new orphan child`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        init()
        host.stop()
        assertEquals(503, host.post("fake2", null, MCP_HOST_INIT).status)
        assertFalse(hosted("fake2"))
    }

    @Test
    fun `child stderr is drained and reported without emitting its content`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val session = init()
        assertTrue(text(call(session, 1, "stderr")).contains("echo="))
        awaitLogged("child stderr emitted")
        assertFalse(log.contains("synthetic-private-stderr"), "stderr content escaped into operator logs")
        assertEquals(1, log.lines().count { it.contains("child stderr emitted") })
    }

    @Test
    fun `shutdown signals stubborn children together within one grace period`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        init("fake2")
        init("fake3")
        val pids = listOf("fake2", "fake3").map { status(it)["pid"]!!.jsonPrimitive.content.toLong() }
        val start = System.nanoTime()
        host.stop()
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        assertTrue(elapsed < 3_000L, "shutdown spent ${elapsed}ms on sequential child grace periods")
        // Each child's exit is awaited on its own completion future, not polled.
        pids.forEach { pid ->
            ProcessHandle.of(pid).ifPresent { it.onExit().get(MCP_HOST_STREAM_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    @Test
    fun `idle sessions retire while another session keeps their shared process alive`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val idle = init()
        val active = init()
        clock.now += 20.minutes.inWholeMilliseconds
        call(active, 1, "echo")
        clock.now += 15.minutes.inWholeMilliseconds
        host.sweep()
        assertEquals(404, host.post("fake", idle, MCP_HOST_LIST).status)
        assertEquals(200, host.post("fake", active, MCP_HOST_LIST).status)
        assertEquals(1, status("fake")["sessions"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `crash backoff stays bounded after more than sixty four crashes`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val session = init()
        repeat(68) { index ->
            call(session, 9, "crash")
            if (index > 0) {
                val refused = json.parseToJsonElement(host.post("fake", session, MCP_HOST_LIST).body!!).jsonObject
                assertTrue(refused.containsKey("error"), "crash ${index + 1} respawned without backoff")
            }
            clock.now += 61_000L
            call(session, 1, "echo")
        }
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
            val refused = json.parseToJsonElement(host.post("fake", a, MCP_HOST_LIST).body!!).jsonObject
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
        // HostedServer.close logs this line and then enters the TERM grace: the sweeper is now inside
        // the teardown this test times status against (an event, not a 200 ms guess).
        awaitLogged("fake2: closing pid")
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
        assertEquals(404, host.post("fake", a, MCP_HOST_LIST).status)
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
            assertEquals(404, host.post("fake", a, MCP_HOST_LIST).status)
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
        assertEquals(404, host.post("fake", a, MCP_HOST_LIST).status)
        assertTrue(text(call(b, 1, "echo", "n", name = "fake2")).endsWith("echo=n"))
        assertTrue(log.contains("evicted"), log.toString())
    }
}

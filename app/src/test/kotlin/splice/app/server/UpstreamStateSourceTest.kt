package splice.app.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.server.UpstreamSignal
import splice.core.server.UpstreamState
import splice.core.server.UpstreamStateReading
import splice.core.topology.ServerState
import splice.upstream.Ticker
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
class UpstreamStateSourceTest {
    @TempDir
    private lateinit var dir: Path
    private val now = Instant.parse("2026-11-24T17:00:00Z")

    @Test
    fun `a relative file supplies the state and a missing file initially leaves the server up`() = runBlocking {
        val config = ServerState(file = "box.state")
        assertEquals(
            UpstreamStateReading(lastError = "state file is missing or not a regular file", lastChecked = now),
            sample(config),
        )
        Files.writeString(dir.resolve("box.state"), " off until $now\n")
        assertEquals(
            UpstreamStateReading(UpstreamSignal(UpstreamState.Off(now)), lastChecked = now),
            sample(config),
        )
    }

    @Test
    fun `the URL supplies JSON and an HTTP failure is not a state signal`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/state") { call ->
            call.use {
                val output = """{"state":"starting","message":"Loading weights."}""".toByteArray()
                call.sendResponseHeaders(200, output.size.toLong())
                call.responseBody.write(output)
            }
        }
        server.createContext("/error") { call -> call.use { call.sendResponseHeaders(503, -1) } }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            assertEquals(
                UpstreamStateReading(UpstreamSignal(UpstreamState.Starting, "Loading weights."), lastChecked = now),
                sample(ServerState(url = "$url/state")),
            )
            assertEquals(
                UpstreamStateReading(lastError = "state URL returned HTTP 503", lastChecked = now),
                sample(ServerState(url = "$url/error")),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an oversized URL signal is rejected without waiting for the body to end`() = runBlocking {
        val finishBody = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/state") { call ->
            call.use {
                call.sendResponseHeaders(200, 0)
                call.responseBody.write(ByteArray(32_768) { 'x'.code.toByte() })
                call.responseBody.flush()
                finishBody.await()
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/state"
            assertEquals(
                UpstreamStateReading(lastError = "state output is too large", lastChecked = now),
                sample(ServerState(url = url)),
            )
        } finally {
            finishBody.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `the command supplies stdout verbatim but output from a failed command is not a signal`() = runBlocking {
        val output = """{"state":"off","message":"Wait; maintenance is in progress."}"""
        assertEquals(
            UpstreamStateReading(
                UpstreamSignal(UpstreamState.Off(), "Wait; maintenance is in progress."),
                lastChecked = now,
            ),
            sample(ServerState(command = listOf("/usr/bin/printf", "%s", output))),
        )
        assertEquals(
            UpstreamStateReading(lastError = "state command exited 7", lastChecked = now),
            sample(ServerState(command = listOf("/bin/sh", "-c", "printf off; exit 7"))),
        )
    }

    @Test
    fun `the command can write more than a small pipe buffer before it exits`() = runBlocking {
        val message = "x".repeat(12_000)
        val output = """{"state":"off","message":"$message"}"""
        assertEquals(
            UpstreamStateReading(UpstreamSignal(UpstreamState.Off(), message), lastChecked = now),
            sample(ServerState(command = listOf("/usr/bin/printf", "%s", output))),
        )
    }

    private suspend fun sample(config: ServerState): UpstreamStateReading {
        val published = CompletableDeferred<Unit>()
        val poll = UpstreamStatePoll(
            config,
            dir,
            Clock.fixed(now, ZoneOffset.UTC),
            Ticker { published.complete(Unit); false },
        )
        poll.open()
        return try {
            withTimeout(10_000) { published.await() }
            poll.reading()
        } finally {
            poll.close()
        }
    }
}

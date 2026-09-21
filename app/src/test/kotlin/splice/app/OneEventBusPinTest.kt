// NEW: V4-134 — the daemon has ONE console event bus, and the route and every producer hold it.
//
// THE HAZARD (V4-126's own note): ControlServer used to build `EventBus()` for itself, so a daemon
// that forgot to hand the route the producers' bus compiled, served /api/events, and streamed a bus
// nobody publishes to — a console watching a quiet daemon forever while every head talked to a
// second bus. That default is gone (an unassigned route answers a named 503), and what is left to
// pin is that the three production lines which join the two sides stay written:
//
//   ControlPlane.start   srv.ports.events = console.bus        the route's bus IS the producers' bus
//   Daemon               console = controlPlane.console  every head gets THAT publisher
//   HeadServerFactory    events = console?.forHead(key)  and hands it to the head, keyed
//
// The first is checked BEHAVIOURALLY on a real ControlPlane — same instance, and an event published
// through the producers' side arrives on the route's stream. The other two assign values the
// compiler cannot check (both have a default so tests can build the classes bare), so they are
// pinned on the source text, the idiom ConsoleWiringPinTest established for the same reason.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.DashboardPage
import splice.control.TurnPathStalled
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.head.HeadLifecycle
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val PIN_TIMEOUT_MS = 10_000L
private const val PIN_POLL_MS = 25L

class OneEventBusPinTest {

    @Test
    fun `the control plane streams the same bus its producers publish to`() = runBlocking {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("v4134-one-bus").resolve("state"))
        val mgmt = MgmtKey(paths)
        val plane = ControlPlane(
            paths,
            ConfigService(paths),
            mgmt,
            DashboardPage { "<!doctype html>" },
            { },
            { },
        )
        val port = ServerSocket(0).use { it.localPort }
        val srv = checkNotNull(
            plane.start(
                controlPort = port,
                heads = emptyMap(),
                failedHeads = { 0 },
                headCount = 0,
                turnPathStalled = TurnPathStalled { emptyList() },
            ),
        ) { "the control plane did not bind :$port" }
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            assertSame(plane.console.bus, srv.ports.events, "the route must stream the producers' bus, not its own")
            val frame = firstFrame(client, port, mgmt.get()) {
                plane.console.forHead("pinned-head").lifecycle(HeadLifecycle.STARTED)
            }
            assertEquals("event: head.state", frame[1], "a producer's event must arrive on the route: $frame")
            assertTrue(frame[2].contains("\"head\":\"pinned-head\""), "it must be THIS producer's event: $frame")
        } finally {
            client.close()
            srv.stop()
            plane.cancelProbes()
        }
    }

    @Test
    fun `the daemon hands every head the control plane's publisher`() {
        assertTrue(
            source("app/src/main/kotlin/splice/app/Daemon.kt").contains("console = controlPlane.console"),
            "Daemon must build HeadServerFactory with `console = controlPlane.console`, or every head " +
                "reports to nobody while the build stays green",
        )
    }

    @Test
    fun `the head factory gives each head its own keyed reporter`() {
        assertTrue(
            source("app/src/main/kotlin/splice/app/head/HeadServerFactory.kt")
                .contains("events = console?.forHead(key)"),
            "HeadServerFactory must set HeadSeams.events from the publisher, keyed by the head it builds",
        )
    }

    /** Opens /api/events, runs [produce] once the stream is live, and returns the first frame's lines
     *  (id, event, data). The stream subscribes before writing its open comment, so producing after
     *  that line cannot race the subscription. */
    private suspend fun firstFrame(client: HttpClient, port: Int, key: String, produce: () -> Unit): List<String> =
        withTimeout(PIN_TIMEOUT_MS) {
            awaitPort(port)
            val open = CompletableDeferred<Unit>()
            val reading = async {
                client.prepareGet("http://127.0.0.1:$port/api/events") {
                    header("Authorization", "Bearer $key")
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    check(channel.readUTF8Line() == ": open") { "the stream must open with its comment" }
                    open.complete(Unit)
                    frameLines(channel)
                }
            }
            open.await()
            produce()
            reading.await()
        }

    /** The next frame's lines: blank lines and comments before it are skipped, the blank after ends it. */
    private suspend fun frameLines(channel: ByteReadChannel): List<String> {
        val lines = mutableListOf<String>()
        var line = channel.readUTF8Line()
        while (line != null && stillReading(lines.isEmpty(), line)) {
            if (line.isNotEmpty() && !line.startsWith(":")) lines.add(line)
            line = channel.readUTF8Line()
        }
        return lines
    }

    private suspend fun awaitPort(port: Int) {
        while (runCatching { java.net.Socket("127.0.0.1", port).close() }.isFailure) delay(PIN_POLL_MS)
    }

    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }

    /** Before the frame starts every line is read (the blank after `: open`, heartbeats); once it
     *  has started, the first blank line ends it. */
    private fun stillReading(nothingYet: Boolean, line: String): Boolean = nothingYet || line.isNotEmpty()
}

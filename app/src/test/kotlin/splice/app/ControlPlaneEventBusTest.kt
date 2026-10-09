// The daemon has ONE console event bus: an event published through the producers' side of a real
// ControlPlane arrives on the route's /api/events stream.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.SilentHeadProbes
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.head.HeadLifecycle
import java.nio.file.Path

private const val BUS_TIMEOUT_MS = 10_000L
private const val BUS_POLL_MS = 25L

class ControlPlaneEventBusTest {

    @Test
    fun `the control plane streams the same bus its producers publish to`(@TempDir tempDir: Path) = runBlocking {
        val paths = StatePaths(baseOverride = tempDir.resolve("state"))
        val mgmt = MgmtKey(paths)
        val plane = ControlPlane(
            DaemonEnvironment(paths, ConfigService(paths), mgmt, { }),
            { },
        )
        val srv = checkNotNull(
            plane.start(
                controlPort = 0, // OS-assigned at bind: no leased port to lose before the bind
                heads = emptyMap(),
                failedHeads = { 0 },
                headCount = 0,
                probes = SilentHeadProbes,
            ),
        ) { "the control plane did not bind" }
        val port = srv.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            assertSame(plane.console.bus, srv.ports.events, "the route must stream the producers' bus, not its own")
            val frame = firstFrame(client, port, mgmt.get()) {
                plane.console.forHead("bus-head").lifecycle(HeadLifecycle.STARTED)
            }
            assertEquals("event: head.state", frame[1], "a producer's event must arrive on the route: $frame")
            assertTrue(frame[2].contains("\"head\":\"bus-head\""), "it must be THIS producer's event: $frame")
        } finally {
            client.close()
            srv.stop()
            plane.cancelProbes()
        }
    }

    /** Opens /api/events, runs [produce] once the stream is live, and returns the first frame's lines
     *  (id, event, data). The stream subscribes before writing its open comment, so producing after
     *  that line cannot race the subscription. */
    private suspend fun firstFrame(client: HttpClient, port: Int, key: String, produce: () -> Unit): List<String> =
        withTimeout(BUS_TIMEOUT_MS) {
            awaitPort(port)
            val open = CompletableDeferred<Unit>()
            val reading = async {
                client.prepareGet("http://127.0.0.1:$port/api/events") {
                    header("Authorization", "Bearer $key")
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    check(channel.readLine() == ": open") { "the stream must open with its comment" }
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
        var line = channel.readLine()
        while (line != null && stillReading(lines.isEmpty(), line)) {
            if (line.isNotEmpty() && !line.startsWith(":")) lines.add(line)
            line = channel.readLine()
        }
        return lines
    }

    private suspend fun awaitPort(port: Int) {
        while (runCatching { java.net.Socket("127.0.0.1", port).close() }.isFailure) delay(BUS_POLL_MS)
    }

    /** Before the frame starts every line is read (the blank after `: open`, heartbeats); once it
     *  has started, the first blank line ends it. */
    private fun stillReading(nothingYet: Boolean, line: String): Boolean = nothingYet || line.isNotEmpty()
}

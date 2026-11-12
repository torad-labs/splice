// NEW: #523 — POST /api/daemon/retire through a real ControlServer over HTTP with the mgmt key. The next daemon asks
// the running one to hand over on this route. A daemon whose launcher cannot start another refuses by name, and the
// daemon that asked then starts the way a restart always has.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Path

private const val HAND_OVER_TIMEOUT_MS = 10_000L

class HandOverRouteTest {

    @Test
    fun `a hand-over request is refused by name until a launcher can start the next daemon`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        // The OS picks the port at start, and it is read back below.
        val control = controlServerWith(
            heads = emptyMap(),
            config = ConfigService(paths),
            health = topologyHealthFor(emptyMap()),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        runBlocking { control.start() }
        val url = "http://127.0.0.1:${control.listeningPort}/api/daemon/retire"
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(HAND_OVER_TIMEOUT_MS) {
                    assertEquals(HttpStatusCode.Unauthorized, client.post(url).status)

                    val unwired = client.post(url) { header("Authorization", "Bearer ${mgmt.get()}") }
                    assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
                    assertTrue("cannot hand over to another" in unwired.bodyAsText(), unwired.bodyAsText())

                    control.ports.lifecycle.handOver = HandOver({}, { false })
                    val taken = client.post(url) { header("Authorization", "Bearer ${mgmt.get()}") }
                    assertEquals(HttpStatusCode.Accepted, taken.status)
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }
}

// NEW: V4-132 — the console's off-request login seam, browser side. When [LoginObserver] is
// present, OAuthLoginFlow reports the authorize URL through it instead of opening a browser on the
// daemon's own (often headless) desktop — awaitCode's branch. The observer fires before the
// loopback callback is awaited, so this drives the flow to completion with a real GET against the
// loopback listener, the same shape a browser redirect would produce.
package splice.app.auth

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

class OAuthLoginObserverTest {

    private class RecordingObserver : LoginObserver {
        val detail = AtomicReference<LoginAnnouncement?>(null)
        override fun announced(detail: LoginAnnouncement) {
            this.detail.set(detail)
        }
    }

    private fun servingToken(body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun `an observed browser login reports the authorize URL through the observer, then completes on the callback`(
        @TempDir tmp: Path,
    ) {
        val redirectPort = ServerSocket(0).use { it.localPort }
        val tokenServer = servingToken("""{"access_token":"tok_observed_browser"}""")
        val observer = RecordingObserver()
        val authorizeUrl = "http://127.0.0.1/unused?state=s1"
        val spec = LoginSpec(
            head = "probe",
            authorizeUrl = authorizeUrl,
            redirectPort = redirectPort,
            redirectPath = "/cb",
            expectedState = "s1",
            tokenUrl = "http://127.0.0.1:${tokenServer.address.port}/token",
            exchangeForm = { code -> "code=$code" },
            authPath = tmp.resolve("auth.json"),
            toAuthJson = { body -> body },
        )
        val client = HttpClient(CIO)

        val ok = try {
            runBlocking {
                val running = async(Dispatchers.IO) { OAuthLoginFlow(TerminalOutput {}).run(spec, observer) }
                awaitAnnouncement(observer)
                assertEquals(authorizeUrl, observer.detail.get()?.browserUrl)
                withTimeout(TIMEOUT_MS) {
                    client.get("http://127.0.0.1:$redirectPort/cb?code=the-code&state=s1")
                }
                running.await()
            }
        } finally {
            client.close()
            tokenServer.stop(0)
        }

        assertTrue(ok, "the observed flow must complete exactly as a browser-driven one would")
        assertTrue(Files.exists(spec.authPath), "the credential is still persisted")
    }

    private suspend fun awaitAnnouncement(observer: RecordingObserver) = withTimeout(TIMEOUT_MS) {
        while (observer.detail.get() == null) delay(POLL_MS)
    }
}

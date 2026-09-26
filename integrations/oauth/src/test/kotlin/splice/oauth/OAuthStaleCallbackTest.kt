// NEW: a sign-in finished in a tab from an EARLIER attempt carries that attempt's state, and the flow
// rightly ignores it. It used to ignore it without a word, so the pane sat silent until the 300 s
// timeout while the operator believed he had signed in (rehearsal-plans-1, 2026-09-25: three codex
// sign-ins, none completed). The pane now says what happened; a request with no state at all is a
// drive-by on the loopback port and stays silent, and the real callback still completes the flow.
package splice.oauth

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import splice.core.testing.TestPorts
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private const val STALE_TIMEOUT_MS = 10_000L
private const val STALE_POLL_MS = 20L

class OAuthStaleCallbackTest {

    private class Announced : LoginObserver {
        val detail = AtomicReference<LoginAnnouncement?>(null)
        override fun announced(detail: LoginAnnouncement) {
            this.detail.set(detail)
        }
    }

    private fun servingToken(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { ex ->
            val bytes = """{"access_token":"tok_stale_probe"}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun `a callback from an earlier attempt is named in the pane and on its page, and the real one still completes`(
        @TempDir tmp: Path,
    ) {
        val redirectPort = TestPorts.reserve()
        val tokenServer = servingToken()
        val lines = CopyOnWriteArrayList<String>()
        val observer = Announced()
        val spec = LoginSpec(
            head = "probe",
            authorizeUrl = "http://127.0.0.1/unused?state=current",
            redirectPort = redirectPort,
            redirectPath = "/cb",
            expectedState = "current",
            tokenUrl = "http://127.0.0.1:${tokenServer.address.port}/token",
            exchangeForm = { code -> "code=$code" },
            authPath = tmp.resolve("auth.json"),
            toAuthJson = { body -> body },
        )
        val client = HttpClient(CIO)
        val base = "http://127.0.0.1:$redirectPort/cb"

        val flow = OAuthLoginFlow(TerminalOutput { lines += it })
        val (stalePage, ok) = try {
            runBlocking {
                val running = async(Dispatchers.IO) { flow.run(spec, observer) }
                withTimeout(STALE_TIMEOUT_MS) {
                    while (observer.detail.get() == null) delay(STALE_POLL_MS)
                }
                val page = withTimeout(STALE_TIMEOUT_MS) {
                    client.get("$base?code=old-code&state=earlier").bodyAsText()
                }
                withTimeout(STALE_TIMEOUT_MS) { client.get(base) }
                withTimeout(STALE_TIMEOUT_MS) { client.get("$base?code=the-code&state=current") }
                page to running.await()
            }
        } finally {
            client.close()
            tokenServer.stop(0)
        }

        val named = lines.filter { "earlier attempt" in it }
        assertEquals(1, named.size, "one line for the stale callback, none for the stateless probe: $lines")
        assertTrue("newest" in named.single(), "the line says what to do: $named")
        assertTrue("earlier attempt" in stalePage, "the browser page says it too: $stalePage")
        assertTrue(ok, "the stale callback must not end the flow; the real one completes it")
    }
}

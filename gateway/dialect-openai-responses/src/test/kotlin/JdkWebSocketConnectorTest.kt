// DR-185: the handshake half of the WS pre-commit window, which had the right budget applied to
// the wrong builder. This is the one suite here that uses a REAL socket, and it has to: the defect
// lives in which JDK builder receives the Duration, so a fake connector cannot see it. The server
// ACCEPTS and then says nothing — TCP connect succeeds, HttpClient.connectTimeout is satisfied, and
// only WebSocket.Builder.connectTimeout can end the upgrade exchange that follows.
//
// Unfixed, this arm does not fail — it HANGS to the test's own ceiling, which is the shape of the
// bug: nothing throws, nothing returns, and the round can never decline to SSE.
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.Cancellables
import splice.dialect.responses.JdkWebSocketConnector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpTimeoutException
import java.net.http.WebSocket
import kotlin.concurrent.thread

private const val HANDSHAKE_BUDGET_MS = 1_500L

// A generous multiple of the budget: the claim is "bounded at all", not "bounded to the
// millisecond", and a loaded box must not turn a real pass into a flake. Unfixed the wait is
// unbounded, so any ceiling separates the two.
private const val PATIENCE_MS = 20_000L

class JdkWebSocketConnectorTest {

    @Test
    fun `a peer that accepts and then says nothing cannot hang the handshake - DR-185`() {
        val server = ServerSocket(0)
        val held = mutableListOf<Socket>()
        val accepter = thread(isDaemon = true) {
            Cancellables.discard(
                runCatching { while (true) held += server.accept() },
                "the accept loop ends when the finally below closes the server socket",
            )
        }
        try {
            val uri = URI.create("ws://127.0.0.1:${server.localPort}/")
            val failure = runBlocking {
                runCatching {
                    withTimeout(PATIENCE_MS) {
                        JdkWebSocketConnector().jdkConnect(
                            uri,
                            emptyMap(),
                            object : WebSocket.Listener {},
                            HANDSHAKE_BUDGET_MS,
                        )
                    }
                }.exceptionOrNull()
            }
            // HttpTimeoutException specifically, never merely "something was thrown": a
            // TimeoutCancellationException here would be the TEST's ceiling firing, which is the
            // unfixed behaviour wearing a green tick. The JDK has to own the bound.
            assertTrue(
                failure is HttpTimeoutException,
                "a stalled upgrade must end as a JDK timeout the round can decline on, within " +
                    "${PATIENCE_MS}ms; got ${failure?.let { it::class.qualifiedName } ?: "a connection"}",
            )
        } finally {
            server.close()
            held.forEach { socket ->
                Cancellables.discard(runCatching { socket.close() }, "fixture teardown is best-effort")
            }
            accepter.interrupt()
        }
    }
}

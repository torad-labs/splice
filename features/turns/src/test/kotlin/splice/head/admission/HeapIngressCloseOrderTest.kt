package splice.head.admission

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.netty.channel.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.memory.HeapBudget
import splice.http.ingress.HeapIngress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HeapIngressCloseOrderTest {
    private val testServer = HeapIngressTestServer()

    @Test
    fun `a route refusal stages close when its reply finishes before request cleanup`() = runBlocking {
        val heap = HeapBudget(2 * 1024 * 1024, 2 * 1024 * 1024)
        val channel = CompletableFuture<Channel>()
        val finishRoute = CompletableDeferred<Unit>()
        val ingress =
            HeapIngress(heap, { 128 * 1024 }, AdmissionErrorBody, shutdownGraceMs = HEAP_INGRESS_TEST_GRACE_MS)
        val server = testServer(ingress, channel) {
            routing {
                post("/") {
                    call.respondText(
                        "statusline body exceeds 65536 bytes",
                        status = HttpStatusCode(413, "Content Too Large"),
                    )
                    // Force the final response write to beat HeapIngress's application-finally cleanup.
                    finishRoute.await()
                }
            }
        }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 70000\r\n\r\n".toByteArray(),
                )
                val accepted = channel.get(5, TimeUnit.SECONDS)
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 413"), reply)
                assertEquals("statusline body exceeds 65536 bytes", reply.substringAfter("\r\n\r\n"))
                assertTrue(reply.lowercase().contains("connection: close"), reply)
                assertTrue(
                    accepted.isOpen,
                    "the complete refusal must half-close output while awaiting the client's FIN",
                )
                socket.shutdownOutput()
                assertTrue(accepted.closeFuture().await(HEAP_INGRESS_TEST_CLOSE_WAIT_SECONDS, TimeUnit.SECONDS))
            }
        } finally {
            finishRoute.complete(Unit)
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }
}

// NEW: how long and how large a request body may be after its headers were admitted: the read-idle bound and the
// chunked body that is charged as it grows. Real HTTP/1 over a socket, the ingress charge read from the heap ledger.
package splice.head.admission

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.HeadVoice
import splice.core.memory.HeapBudget
import splice.core.memory.HeapWeights
import splice.http.ingress.HeapIngress
import java.net.Socket

private const val CLOSE_WAIT_SECONDS = 15L

class IngressBodyBoundsTest {
    private fun testServer(ingress: HeapIngress, routes: Application.() -> Unit) =
        embeddedServer(
            Netty,
            serverConfig {
                module {
                    ingress.install(this)
                    routes()
                }
            },
        ) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            channelPipelineConfig = { pipeline -> ingress.install(pipeline) }
        }

    private fun chunk(size: Int) = "${size.toString(16)}\r\n${"x".repeat(size)}\r\n"

    @Test
    fun `several small chunked bodies at once are each charged near their real size`() = runBlocking {
        val heap = HeapBudget(Long.MAX_VALUE, budgetBytes = 8L * 1024 * 1024)
        val server =
            testServer(
                HeapIngress(heap, { 4 * 1024 * 1024 }, AdmissionErrorBody(HeadVoice("splice", loginCommand = null))),
            ) {
                routing { post("/") { call.respondText(call.receiveText()) } }
            }
        server.start(false)
        val sockets = mutableListOf<Socket>()
        try {
            val port = server.engine.resolvedConnectors().single().port
            repeat(8) {
                val socket = Socket("127.0.0.1", port)
                sockets += socket
                socket.getOutputStream().write(
                    ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk(100))
                        .toByteArray(),
                )
                socket.getOutputStream().flush()
            }
            val allHeld = 8L * (HeapWeights.CONNECTION_BYTES + HeapWeights.ingress(128 * 1024L))
            withTimeout(CLOSE_WAIT_SECONDS * 1000) {
                while (heap.limitBytes - heap.available.value < 8L * HeapWeights.CONNECTION_BYTES) yield()
            }
            val charged = heap.limitBytes - heap.available.value
            // Eight cap-sized charges would be about 208 MB each against an 8 MiB budget: none would be admitted.
            assertTrue(charged <= allHeld, "eight small chunked bodies charged $charged bytes, not near their size")
        } finally {
            sockets.forEach { it.close() }
            server.stop(0, 1000)
        }
    }

    @Test
    fun `a chunked body past the cap is refused mid-body and returns its charge`() = runBlocking {
        val heap = HeapBudget(Long.MAX_VALUE, budgetBytes = 4L * 1024 * 1024)
        val server =
            testServer(HeapIngress(heap, { 32 * 1024 }, AdmissionErrorBody(HeadVoice("splice", loginCommand = null)))) {
                routing { post("/") { call.respondText(call.receiveText()) } }
            }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = CLOSE_WAIT_SECONDS.toInt() * 1000
                val head = "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                socket.getOutputStream().write((head + chunk(16 * 1024) + chunk(40 * 1024)).toByteArray())
                socket.getOutputStream().flush()
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 413"), reply)
                assertTrue(reply.contains("[splice] This request is larger than the 32 KB splice accepts."), reply)
                assertTrue(reply.contains("Connection: close", ignoreCase = true), reply)
            }
            withTimeout(CLOSE_WAIT_SECONDS * 1000) { while (heap.available.value != heap.limitBytes) yield() }
        } finally {
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }

    @Test
    fun `a chunked body that outgrows the heap is refused mid-body and returns its charge`() = runBlocking {
        val heap = HeapBudget(Long.MAX_VALUE, budgetBytes = 1024L * 1024)
        val server =
            testServer(
                HeapIngress(heap, { 1024 * 1024 }, AdmissionErrorBody(HeadVoice("splice", loginCommand = null))),
            ) {
                routing { post("/") { call.respondText(call.receiveText()) } }
            }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = CLOSE_WAIT_SECONDS.toInt() * 1000
                socket.getOutputStream().write(
                    ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk(200 * 1024))
                        .toByteArray(),
                )
                socket.getOutputStream().flush()
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 529"), reply)
                assertTrue(reply.contains("overloaded_error"), reply)
            }
            withTimeout(CLOSE_WAIT_SECONDS * 1000) { while (heap.available.value != heap.limitBytes) yield() }
        } finally {
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }

    @Test
    fun `a body that stalls after its headers is closed at the read bound and returns its charge`() = runBlocking {
        val heap = HeapBudget(Long.MAX_VALUE, budgetBytes = 256 * 1024)
        val server =
            testServer(
                HeapIngress(heap, {
                    32 * 1024
                }, AdmissionErrorBody(HeadVoice("splice", loginCommand = null)), readIdleMs = { 300L }),
            ) {
                routing { post("/") { call.respondText(call.receiveText()) } }
            }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = CLOSE_WAIT_SECONDS.toInt() * 1000
                socket.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\npartial".toByteArray(),
                )
                socket.getOutputStream().flush()
                // The client sends nothing more. Only the server's own bound can end this read, with its EOF.
                assertEquals(-1, socket.getInputStream().read(), "the stalled body's connection must be closed")
            }
            withTimeout(CLOSE_WAIT_SECONDS * 1000) { while (heap.available.value != heap.limitBytes) yield() }
        } finally {
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value, "every charge of the stalled request is returned")
    }
}

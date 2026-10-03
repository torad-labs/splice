// NEW: real HTTP/1 ingress controls, including refusals before continue and ordered SSE tails.
package splice.head.admission

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.memory.HeapBudget
import splice.http.ingress.HeapIngress
import java.io.ByteArrayInputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HeapIngressTest {
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

    private fun heldServer(
        ingress: HeapIngress,
        streaming: CompletableDeferred<Unit>,
        finishStream: CompletableDeferred<Unit>,
    ) = testServer(ingress) {
        routing {
            get("/held") {
                call.respondTextWriter {
                    write("first-response-start\n")
                    flush()
                    streaming.complete(Unit)
                    finishStream.await()
                    write("first-response-end\n")
                }
            }
            post("/next") { call.respondText(call.receiveText()) }
        }
    }

    private fun readReply(socket: Socket): CompletableFuture<String> = CompletableFuture.supplyAsync {
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `large concurrent senders receive complete overload replies without a body read`() = runBlocking {
        val bytes = ByteArray(32 * 1024 * 1024)
        val heap = HeapBudget(Long.MAX_VALUE, splice.core.memory.HeapWeights.request(bytes.size.toLong()) + 1024 * 1024)
        val hold = checkNotNull(heap.reserve(heap.limitBytes - 128 * 1024))
        val server = testServer(HeapIngress(heap, bytes.size.toLong(), AdmissionErrorBody)) {
            routing { post("/") { error("an overloaded body must not enter routing") } }
        }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
                val publisher = HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) },
                    bytes.size.toLong(),
                )
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/"))
                    .POST(publisher).build()
                val answers = List(16) { client.sendAsync(request, HttpResponse.BodyHandlers.ofString()) }
                CompletableFuture.allOf(*answers.toTypedArray()).get(30, TimeUnit.SECONDS)
                answers.forEach { answer ->
                    val response = answer.get()
                    assertEquals(529, response.statusCode())
                    assertTrue(response.body().contains("overloaded_error"))
                }
            }
        } finally {
            server.stop(0, 1000)
            hold.close()
        }
    }

    @Test
    fun `an empty call still has to reserve its request metadata`() = runBlocking {
        val heap = HeapBudget(1024 * 1024, 64 * 1024 + 1024)
        val server = testServer(HeapIngress(heap, 1024, AdmissionErrorBody)) {
            routing { post("/") { call.respondText("must not allocate an uncharged call") } }
        }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 413"), reply)
                assertTrue(reply.contains("process heap limit is ${heap.limitBytes} bytes"), reply)
                assertFalse(reply.contains("retry"), reply)
            }
        } finally {
            server.stop(0, 1000)
        }
    }

    @Test
    fun `a declared refused body receives overload before any continue or body read`() = runBlocking {
        val heap = HeapBudget(1024 * 1024, 384 * 1024)
        val hold = requireNotNull(heap.reserve(heap.limitBytes))
        val ingress = HeapIngress(heap, 32 * 1024, AdmissionErrorBody)
        val server = testServer(ingress) {
            routing { post("/") { error("a refused body must not enter routing") } }
        }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 32768\r\nExpect: 100-continue\r\n\r\n"
                        .toByteArray(),
                )
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 529"), reply)
                assertFalse(reply.contains("100 Continue"), reply)
                assertTrue(reply.contains("overloaded_error"), reply)
            }
        } finally {
            server.stop(0, 1000)
            hold.close()
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }

    @Test
    fun `an unfit chunked body gets a permanent limit without waiting for its final chunk`() = runBlocking {
        val heap = HeapBudget(1024 * 1024, 128 * 1024)
        val ingress = HeapIngress(heap, 32 * 1024, AdmissionErrorBody)
        val server = testServer(ingress) {
            routing { post("/") { error("a cap-backed refusal must not read chunks") } }
        }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(),
                )
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 413"), reply)
                assertTrue(reply.contains("materialization heap limit is ${heap.limitBytes} bytes"), reply)
                assertTrue(reply.contains("invalid_request_error"), reply)
                assertFalse(reply.contains("retry"), reply)
            }
        } finally {
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }

    @Test
    fun `an admitted unread body is settled without waiting for its missing bytes`() = runBlocking {
        val heap = HeapBudget(1024 * 1024, 128 * 1024)
        val ingress = HeapIngress(heap, 32 * 1024, AdmissionErrorBody)
        val server = testServer(ingress) {
            routing { post("/") { call.respondText("rejected by the route") } }
        }
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1024\r\n\r\n".toByteArray(),
                )
                val reply = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
                assertTrue(reply.contains("rejected by the route"), reply)
            }
        } finally {
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }

    @Test
    fun `a pipelined overload cannot overtake a preceding streamed response`() = runBlocking {
        val heap = HeapBudget(1024 * 1024, 128 * 1024)
        val ingress = HeapIngress(heap, 32 * 1024, AdmissionErrorBody)
        val streaming = CompletableDeferred<Unit>()
        val finishStream = CompletableDeferred<Unit>()
        val server = heldServer(ingress, streaming, finishStream)
        server.start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val output = socket.getOutputStream()
                output.write("GET /held HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                streaming.await()
                val hold = requireNotNull(heap.reserve(heap.available.value))
                try {
                    output.write(
                        "POST /next HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1024\r\n\r\n".toByteArray(),
                    )
                    val response = readReply(socket)
                    finishStream.complete(Unit)
                    val reply = response.get(5, TimeUnit.SECONDS)
                    assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
                    val end = reply.indexOf("first-response-end")
                    val overload = reply.indexOf("HTTP/1.1 529")
                    assertTrue(end >= 0 && overload > end, reply)
                } finally {
                    hold.close()
                    finishStream.complete(Unit)
                }
            }
        } finally {
            finishStream.complete(Unit)
            server.stop(0, 1000)
        }
        assertEquals(heap.limitBytes, heap.available.value)
    }
}

package splice.app.mods

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.handover.MOD_HANDOVER_PATH
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandover
import splice.app.mods.handover.ModHandoverBatch
import splice.app.mods.run.ModEvent
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ModHandoverFlushTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a retried receipt does not tell the mod twice`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            rig.retire()
            HttpClient(CIO).use { client ->
                val payload = JsonObject(mapOf("session" to JsonPrimitive("repeated")))
                val event = ModEvent("turn.complete", "repeated", payload)
                val receipt = ModHandoverBatch(listOf(ModDelivery(event)), sender = "old", ordinal = 1)
                repeat(2) {
                    val response = client.post(rig.handoverUrl) {
                        bearerAuth(rig.key.get())
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(receipt))
                    }
                    assertEquals(HttpStatusCode.NoContent, response.status)
                }
            }
            rig.successorTurn("barrier").completed(
                splice.core.turn.ObservedTurnEnd("ok", splice.core.turn.ObservedUsage(0, 0, 0, 0), 1, 2),
            )
            val events = rig.through("turn.complete", "barrier")
            assertEquals(1, events.count { it.name == "turn.complete" && it.session == "repeated" })
        }
    }

    @Test
    fun `the successor reports an unconfirmed event when the old daemon cuts its flush short`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            rig.retire()
            HttpClient(CIO).use { client ->
                StalledHandover(client, rig.handoverUrl, rig.key.get()).use { relay ->
                    val sender = ModHandover(CoroutineScope(coroutineContext), relay.port, rig.key, LogSink {})
                    sender.offer(ModDelivery(ModEvent("turn.start", "session", JsonObject(emptyMap()))))
                    sender.leave()
                    withTimeout(10_000) { relay.batchStarted.await() }
                    sender.finish()
                    val response = client.get(rig.modsUrl) { bearerAuth(rig.key.get()) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val catalog = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals(1L, catalog.getValue("handoverDropped").jsonPrimitive.long)
                }
            }
        }
    }
}

/** The event request gets no reply; a separate loss report can still reach the real successor. */
private class StalledHandover(
    private val client: HttpClient,
    private val successor: String,
    private val key: String,
) : AutoCloseable {
    val batchStarted = CompletableDeferred<Unit>()
    private val release = CountDownLatch(1)
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext(MOD_HANDOVER_PATH, ::receive)
        start()
    }
    val port: Int get() = relay.address.port

    private fun receive(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val batch = Json.decodeFromString<ModHandoverBatch>(body)
            if (batch.events.isNotEmpty()) {
                batchStarted.complete(Unit)
                release.await()
            } else {
                val status = runBlocking {
                    client.post(successor) {
                        bearerAuth(key)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status
                }
                exchange.sendResponseHeaders(status.value, -1)
            }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        release.countDown()
        relay.stop(0)
        pool.close()
    }
}

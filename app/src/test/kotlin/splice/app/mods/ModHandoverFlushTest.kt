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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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
    fun `a batch whose reply was lost is not told again after a second quick handover`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            HttpClient(CIO).use { client ->
                ChangingHandover(client, rig.oldHandoverUrl, rig.key.get()).use { relay ->
                    val sender = ModHandover(CoroutineScope(coroutineContext), relay.port, rig.key, LogSink {})
                    val event = lostReply("chained").events.single()
                    sender.offer(event)
                    sender.leave()
                    withTimeout(10_000) { relay.lostReply.await() }
                    rig.through("turn.complete", "chained")
                    rig.retire()
                    relay.redirect(rig.handoverUrl)
                    withTimeout(10_000) { relay.confirmedRetry.await() }
                    sender.finish()
                }
            }
            assertEquals(1, barrier(rig).count { it.name == "turn.complete" && it.session == "chained" })
        }
    }

    @Test
    fun `an old sender that retries before the receipts arrive is held, then not told twice`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            val lost = lostReply("raced")
            HttpClient(CIO).use { client ->
                assertEquals(HttpStatusCode.NoContent, client.send(rig.oldHandoverUrl, rig.key.get(), lost))
                rig.through("turn.complete", "raced")
                rig.retire(leave = false)
                rig.openSuccessor()
                val earlier = ModHandoverBatch(emptyList(), sender = "1/earlier", startsComplete = true)
                assertEquals(HttpStatusCode.ServiceUnavailable, client.send(rig.handoverUrl, rig.key.get(), earlier))
                assertEquals(HttpStatusCode.ServiceUnavailable, client.send(rig.handoverUrl, rig.key.get(), lost))
                rig.releaseRelay()
                rig.recoverSessions()
                assertEquals(HttpStatusCode.NoContent, client.send(rig.handoverUrl, rig.key.get(), lost))
            }
            assertEquals(1, barrier(rig).count { it.name == "turn.complete" && it.session == "raced" })
        }
    }

    @Test
    fun `a stop that cancels the flush still logs how many events were not confirmed`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.installRecorder()
            rig.retire()
            val lines = CopyOnWriteArrayList<String>()
            val scope = CoroutineScope(SupervisorJob())
            HttpClient(CIO).use { client ->
                StalledHandover(client, rig.handoverUrl, rig.key.get(), holdReport = true).use { relay ->
                    val sender = ModHandover(scope, relay.port, rig.key, LogSink { lines.add(it) })
                    sender.offer(ModDelivery(ModEvent("turn.start", "session", JsonObject(emptyMap()))))
                    sender.leave()
                    withTimeout(10_000) { relay.batchStarted.await() }
                    val stopping = launch(start = CoroutineStart.UNDISPATCHED) { sender.finish() }
                    withTimeout(18_000) { relay.reportStarted.await() }
                    stopping.cancelAndJoin()
                }
            }
            scope.cancel()
            assertTrue(lines.any { "1 events were not delivered" in it }, lines.toString())
        }
    }

    private fun lostReply(session: String): ModHandoverBatch {
        val event = ModEvent("turn.complete", session, JsonObject(mapOf("session" to JsonPrimitive(session))))
        return ModHandoverBatch(listOf(ModDelivery(event)), sender = "first", ordinal = 3)
    }

    private suspend fun HttpClient.send(url: String, key: String, batch: ModHandoverBatch) = post(url) {
        bearerAuth(key)
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(batch))
    }.status

    private suspend fun barrier(rig: ModHandoverTestRig): List<RecordedModEvent> {
        rig.successorTurn("barrier").completed(
            splice.core.turn.ObservedTurnEnd("ok", splice.core.turn.ObservedUsage(0, 0, 0, 0), 1, 2),
        )
        return rig.through("turn.complete", "barrier")
    }

    @Test
    fun `an acknowledged delivery without a recipient is counted`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            rig.retire()
            HttpClient(CIO).use { client ->
                val event = ModEvent("turn.complete", "lost", JsonObject(emptyMap()))
                val response = client.post(rig.handoverUrl) {
                    bearerAuth(rig.key.get())
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ModHandoverBatch(listOf(ModDelivery(event)))))
                }
                assertEquals(HttpStatusCode.NoContent, response.status)
                val catalog = client.get(rig.modsUrl) { bearerAuth(rig.key.get()) }
                val mods = Json.parseToJsonElement(catalog.bodyAsText()).jsonObject
                assertEquals(1L, mods.getValue("handoverDropped").jsonPrimitive.long)
            }
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

/** A first receipt reaches the retiring daemon; its HTTP reply is lost, so the sender really retries. */
private class ChangingHandover(
    private val client: HttpClient,
    private val retiring: String,
    private val key: String,
) : AutoCloseable {
    private val target = AtomicReference(retiring)
    val lostReply = CompletableDeferred<Unit>()
    val confirmedRetry = CompletableDeferred<Unit>()
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext(MOD_HANDOVER_PATH, ::receive)
        start()
    }

    val port: Int get() = relay.address.port

    fun redirect(url: String) {
        target.set(url)
    }

    private fun receive(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val batch = Json.decodeFromString<ModHandoverBatch>(body)
            val to = target.get()
            val status = runBlocking { client.relay(to, key, body) }
            if (status == HttpStatusCode.NoContent && batch.events.isNotEmpty()) {
                if (to == retiring) {
                    lostReply.complete(Unit)
                    return
                }
                confirmedRetry.complete(Unit)
            }
            exchange.sendResponseHeaders(status.value, -1)
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        relay.stop(0)
        pool.close()
    }
}

private suspend fun HttpClient.relay(url: String, key: String, body: String) = post(url) {
    bearerAuth(key)
    contentType(ContentType.Application.Json)
    setBody(body)
}.status

/** The event request gets no reply; a separate loss report can still reach the real successor. */
private class StalledHandover(
    private val client: HttpClient,
    private val successor: String,
    private val key: String,
    private val holdReport: Boolean = false,
) : AutoCloseable {
    val batchStarted = CompletableDeferred<Unit>()
    val reportStarted = CompletableDeferred<Unit>()
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
            when {
                batch.events.isNotEmpty() -> {
                    batchStarted.complete(Unit)
                    release.await()
                }
                holdReport && batch.dropped > 0 -> {
                    reportStarted.complete(Unit)
                    release.await()
                }
                else -> {
                    val status = runBlocking { client.relay(successor, key, body) }
                    exchange.sendResponseHeaders(status.value, -1)
                }
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

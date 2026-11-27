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
import splice.app.control.ControlAuth
import splice.app.control.controlServerFor
import splice.app.mods.handover.MOD_HANDOVER_PATH
import splice.app.mods.handover.MOD_HANDOVER_RECEIPT_MS
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandover
import splice.app.mods.handover.ModHandoverBatch
import splice.app.mods.handover.ModHandoverStarts
import splice.app.mods.run.ModEvent
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.testing.TestPorts
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val CHAIN_HANDOVERS = 18 // why: more daemons in a row than the old sixteen-sender list could hold

class ModHandoverFlushTest {
    @TempDir lateinit var root: Path

    @Test
    fun `an event held behind mod start is accounted for when its dispatch is aborted`() = runBlocking {
        val ticks = AtomicLong(1)
        val clock = WallClock { ticks.getAndAdd(31_000) }
        ModHandoverTestRig(root, clock, holdStart = true).use { rig ->
            rig.installRecorder()
            HttpClient(CIO).use { client ->
                val held = lostReply("behind-start").events + lostReply("queued-after").events
                val accepted = client.send(rig.oldHandoverUrl, rig.key.get(), ModHandoverBatch(held))
                assertEquals(HttpStatusCode.NoContent, accepted)
                rig.awaitMods(client, "stuck", at = rig.oldModsUrl)
                rig.retire()
                rig.finish()
                val events = barrier(rig)
                val dropped = client.get(rig.modsUrl) { bearerAuth(rig.key.get()) }.bodyAsText()
                    .let { Json.parseToJsonElement(it).jsonObject["handoverDropped"]?.jsonPrimitive?.long ?: 0L }
                assertEquals(1, events.count { it.session == "queued-after" })
                assertEquals(1L, events.count { it.session == "behind-start" } + dropped)
            }
        }
    }

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
    fun `a batch whose reply was lost is not told again after a chain of quick graceful handovers`() = runBlocking {
        val chain = (0..CHAIN_HANDOVERS).map { ChainDaemon(Files.createDirectories(root.resolve("daemon-$it"))) }
        val scope = CoroutineScope(SupervisorJob())
        try {
            HttpClient(CIO).use { client ->
                val first = chain.first()
                first.open()
                first.installRecorder(client)
                ChangingHandover(client, first.handoverUrl, first.key.get()).use { relay ->
                    val sender = ModHandover(scope, relay.port, first.key, LogSink {})
                    sender.offer(first.delivery("chained-far"))
                    sender.leave()
                    withTimeout(10_000) { relay.lostReply.await() }
                    val original = first.rig.through("recorder", "turn.complete", "chained-far")
                    assertEquals(1, original.count { it.session == "chained-far" })
                    // Each relay supplies a fresh sender receipt while the first sender keeps retrying.
                    chain.zipWithNext().forEach { (from, to) -> withTimeout(20_000) { from.handOverTo(to) } }
                    val last = chain.last()
                    last.installRecorder(client)
                    relay.redirect(last.handoverUrl, last.key.get())
                    withTimeout(10_000) { relay.confirmedRetry.await() }
                    sender.finish()
                    val barrier = ModHandoverBatch(listOf(last.delivery("chain-barrier")), sender = "barrier")
                    assertEquals(HttpStatusCode.NoContent, client.send(last.handoverUrl, last.key.get(), barrier))
                    val told = last.rig.through("recorder", "turn.complete", "chain-barrier")
                    assertEquals(0, told.count { it.session == "chained-far" }, "the retried batch was told again")
                }
            }
        } finally {
            scope.cancel()
            chain.forEach { it.close() }
        }
    }

    @Test
    fun `a wall clock jump does not expire a recent carried receipt or renew an old one`() = runBlocking {
        val wall = AtomicLong(System.currentTimeMillis())
        ModHandoverTestRig(root, WallClock(wall::get)).use { rig ->
            rig.installRecorder()
            rig.retire()
            HttpClient(CIO).use { client ->
                val carried = ModHandoverBatch(
                    emptyList(),
                    sender = "carrier",
                    startsComplete = true,
                    accepted = mapOf("old-a" to 3L, "old-b" to 3L),
                    acceptedAgeMs = mapOf("old-a" to 2 * MOD_HANDOVER_RECEIPT_MS, "old-b" to 600_000L),
                )
                assertEquals(HttpStatusCode.NoContent, client.send(rig.handoverUrl, rig.key.get(), carried))
                wall.addAndGet(2 * MOD_HANDOVER_RECEIPT_MS)
                for (sender in listOf("old-a", "old-b")) {
                    val event = ModEvent("turn.complete", sender, JsonObject(mapOf("session" to JsonPrimitive(sender))))
                    val retry = ModHandoverBatch(listOf(ModDelivery(event)), sender = sender, ordinal = 3)
                    assertEquals(HttpStatusCode.NoContent, client.send(rig.handoverUrl, rig.key.get(), retry))
                }
            }
            val told = barrier(rig)
            assertEquals(1, told.count { it.session == "old-a" }, "a receipt past the window is no longer kept")
            assertEquals(0, told.count { it.session == "old-b" }, "a receipt inside the window was forgotten")
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

/** One daemon of a chain: a real runner behind its own control routes, handing over to the next in turn. */
private class ChainDaemon(dir: Path) : AutoCloseable {
    val rig = ModEventsTestRig(dir)
    val key = MgmtKey(rig.runner.paths, LogSink {})
    val port = TestPorts.reserve()
    val starts = ModHandoverStarts(true, ProcessHandle.current().pid())
    val handoverUrl get() = "$url$MOD_HANDOVER_PATH"
    val url get() = "http://127.0.0.1:$port"
    private val server = controlServerFor(
        port = port,
        heads = emptyMap(),
        config = ConfigService(rig.runner.paths),
        auth = ControlAuth(mgmtKey = key, log = LogSink {}),
    ).apply {
        ports.mods = rig.runner.mods
        ports.modRunner = rig.runner.runner
    }

    suspend fun open() {
        server.start()
        rig.runner.runner.start()
    }

    /** The successor opens first and waits for this daemon's last transfer batch, as a real successor does. */
    suspend fun handOverTo(next: ChainDaemon) {
        next.rig.runner.runner.handoverStarts = next.starts
        next.open()
        rig.runner.runner.beginHandover(next.port, next.key)
        rig.runner.runner.leave()
        rig.runner.runner.stop("handover")
        withTimeout(10_000) { next.starts.await() }
        server.stop()
    }

    suspend fun installRecorder(client: HttpClient) = rig.install(client, "recorder", base = url)

    fun delivery(session: String): ModDelivery {
        val payload = JsonObject(mapOf("session" to JsonPrimitive(session)))
        return ModDelivery(ModEvent("turn.complete", session, payload))
    }

    override fun close() = runBlocking {
        server.stop()
        rig.runner.runner.stop()
    }
}

/** A first receipt reaches the retiring daemon; its HTTP reply is lost, so the sender really retries. */
private class ChangingHandover(
    private val client: HttpClient,
    private val retiring: String,
    private val key: String,
) : AutoCloseable {
    private val target = AtomicReference(Target(retiring, key))
    val lostReply = CompletableDeferred<Unit>()
    val confirmedRetry = CompletableDeferred<Unit>()
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext(MOD_HANDOVER_PATH, ::receive)
        start()
    }

    val port: Int get() = relay.address.port

    fun redirect(url: String, token: String = key) {
        target.set(Target(url, token))
    }

    private fun receive(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val batch = Json.decodeFromString<ModHandoverBatch>(body)
            val to = target.get()
            val status = runBlocking { client.relay(to.url, to.key, body) }
            if (status == HttpStatusCode.NoContent && batch.events.isNotEmpty()) {
                if (to.url == retiring) {
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

    private data class Target(val url: String, val key: String)
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

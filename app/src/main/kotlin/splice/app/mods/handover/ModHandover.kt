package splice.app.mods.handover

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import splice.core.config.MgmtKey
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// why: finishing the retired daemon cannot wait indefinitely for a successor that never opens.
private const val HANDOVER_FLUSH_MS = 5000L

/** One writer, no retry of a POST: a lost response does not prove the receiver missed its events. */
internal class ModHandover(scope: CoroutineScope, port: Int, key: MgmtKey, private val log: LogSink) {
    private val url = "http://127.0.0.1:$port$MOD_HANDOVER_PATH"
    private val ready = CompletableDeferred<Unit>()
    private val queued = Channel<ModDelivery>(MOD_HANDOVER_CAPACITY)
    private val prefix = ArrayDeque<ModDelivery>()
    private val slots = AtomicInteger()
    private val outstanding = AtomicLong()
    private val missed = AtomicLong()
    private var lastFailure: String? = null
    private val worker = scope.launch {
        ready.await()
        HttpClient(Java) { followRedirects = false }.use { client -> send(client, key.get()) }
    }

    /** The unwritten pipe prefix goes first, even when the turn publishes more events during the switch. */
    fun prefix(delivery: ModDelivery) {
        if (reserve()) prefix.addLast(delivery)
    }

    fun offer(delivery: ModDelivery) {
        if (!reserve()) return
        if (queued.trySend(delivery).isFailure) {
            slots.decrementAndGet()
            missed.incrementAndGet()
        }
    }

    private fun reserve(): Boolean {
        outstanding.incrementAndGet()
        if (slots.getAndUpdate { if (it < MOD_HANDOVER_CAPACITY) it + 1 else it } < MOD_HANDOVER_CAPACITY) return true
        missed.incrementAndGet()
        return false
    }

    fun leave() {
        ready.complete(Unit)
    }

    suspend fun finish() {
        queued.close()
        if (withTimeoutOrNull(HANDOVER_FLUSH_MS) { worker.join() } == null) worker.cancelAndJoin()
        val missing = outstanding.getAndSet(0)
        val why = lastFailure?.let { ": $it" }.orEmpty()
        if (missing > 0) log("[mods] $missing events were not delivered across the handover$why\n")
    }

    private suspend fun send(client: HttpClient, key: String) {
        while (true) {
            val events = nextBatch() ?: break
            slots.addAndGet(-events.size)
            val dropped = missed.get()
            if (post(client, key, ModHandoverBatch(events, dropped))) {
                outstanding.addAndGet(-events.size.toLong() - dropped)
                missed.addAndGet(-dropped)
            } else {
                missed.addAndGet(events.size.toLong())
            }
        }
        val dropped = missed.get()
        if (dropped > 0 && post(client, key, ModHandoverBatch(emptyList(), dropped))) {
            outstanding.addAndGet(-dropped)
        }
    }

    private suspend fun nextBatch(): List<ModDelivery>? {
        val first = prefix.removeFirstOrNull() ?: queued.receiveCatching().getOrNull() ?: return null
        return buildList {
            add(first)
            while (size < MOD_HANDOVER_BATCH_SIZE) {
                add(prefix.removeFirstOrNull() ?: queued.tryReceive().getOrNull() ?: break)
            }
        }
    }

    private suspend fun post(client: HttpClient, key: String, batch: ModHandoverBatch): Boolean =
        Cancellables.runCatchingCancellable {
            client.post(url) {
                bearerAuth(key)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(batch))
            }.status == HttpStatusCode.NoContent
        }.onFailure { lastFailure = SafeFailureText.render(it) }.getOrDefault(false)
}

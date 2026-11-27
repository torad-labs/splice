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

// why: a cut-short flush still tells a reachable successor how many events it never confirmed, but a successor that
// does not answer at once must not hold the old daemon's stop.
private const val HANDOVER_REPORT_MS = 1000L

/**
 * One writer, no retry of a POST: a lost response does not prove the receiver missed its events. So the count the
 * successor records is of events it did not confirm, which can include events it did receive when only the answer was
 * lost.
 */
internal class ModHandover(scope: CoroutineScope, port: Int, private val key: MgmtKey, private val log: LogSink) {
    private val url = "http://127.0.0.1:$port$MOD_HANDOVER_PATH"
    private val ready = CompletableDeferred<Unit>()
    private val queued = Channel<ModDelivery>(MOD_HANDOVER_CAPACITY)
    private val prefix = ArrayDeque<ModDelivery>()
    private val slots = AtomicInteger()
    private val outstanding = AtomicLong()
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
        if (queued.trySend(delivery).isFailure) slots.decrementAndGet()
    }

    private fun reserve(): Boolean {
        outstanding.incrementAndGet()
        return slots.getAndUpdate { if (it < MOD_HANDOVER_CAPACITY) it + 1 else it } < MOD_HANDOVER_CAPACITY
    }

    fun leave() {
        ready.complete(Unit)
    }

    suspend fun finish() {
        queued.close()
        if (withTimeoutOrNull(HANDOVER_FLUSH_MS) { worker.join() } == null) worker.cancelAndJoin()
        // Exactly one report, after the writer is gone: a second one could count the same events twice.
        val missing = outstanding.getAndSet(0)
        if (missing > 0 && !report(missing)) {
            val why = lastFailure?.let { ": $it" }.orEmpty()
            log("[mods] $missing events were not delivered across the handover$why\n")
        }
    }

    /** True only when the successor answered that it recorded [count]. A successor that never opened cannot. */
    private suspend fun report(count: Long): Boolean {
        if (!ready.isCompleted) return false
        val recorded = withTimeoutOrNull(HANDOVER_REPORT_MS) {
            HttpClient(Java) { followRedirects = false }.use { client ->
                post(client, key.get(), ModHandoverBatch(emptyList(), count))
            }
        }
        if (recorded == null) lastFailure = "the next daemon did not answer within ${HANDOVER_REPORT_MS / 1000} s"
        return recorded == true
    }

    private suspend fun send(client: HttpClient, key: String) {
        while (true) {
            val events = nextBatch() ?: break
            slots.addAndGet(-events.size)
            // An event the host hides on purpose was handled, not missed. It is sealed here, off the turn's thread.
            val sealed = events.mapNotNull { delivery -> delivery.event.sealed()?.let { delivery.copy(event = it) } }
            outstanding.addAndGet(sealed.size.toLong() - events.size)
            if (sealed.isNotEmpty() && post(client, key, ModHandoverBatch(sealed))) {
                outstanding.addAndGet(-sealed.size.toLong())
            }
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

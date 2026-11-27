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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import splice.app.mods.sessions.ModPendingSession
import splice.core.config.MgmtKey
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessWaiter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// why: finishing the retired daemon cannot wait indefinitely for a successor that never opens.
private const val HANDOVER_FLUSH_MS = 12_000L

// why: a connection can reach the inherited socket before the server opens.
private const val HANDOVER_ATTEMPT_MS = 1500L
private const val HANDOVER_RETRY_MS = 100L // why: do not spin on a refused local connection

// why: a cut-short flush still tells a reachable successor how many events it never confirmed, but a successor that
// does not answer at once must not hold the old daemon's stop.
private const val HANDOVER_REPORT_MS = 1000L

/** A single ordered writer retries the same receipt identity, not a new delivery. */
internal class ModHandover(
    scope: CoroutineScope,
    port: Int,
    private val key: MgmtKey,
    private val log: LogSink,
    private val accepted: ModReceipts = ModReceipts(),
) {
    private val url = "http://127.0.0.1:$port$MOD_HANDOVER_PATH"
    private val ready = CompletableDeferred<Collection<ModPendingSession>>()
    private val sender = "${ProcessHandle.current().pid()}/${UUID.randomUUID()}"

    @Volatile private var ordinal = 0L

    private val waiter = ProcessWaiter()
    private var waitingForSuccessor = false
    private val queued = Channel<ModDelivery>(MOD_HANDOVER_CAPACITY)
    private val prefix = ArrayDeque<ModDelivery>()
    private val slots = AtomicInteger()
    private val progress = AtomicReference<RelayProgress?>(RelayProgress(0))
    private var lastFailure: String? = null
    private val worker = scope.launch {
        val starts = ready.await()
        HttpClient(Java) { followRedirects = false }.use { client ->
            val secret = key.get()
            confirmStarts(client, secret, starts)
            send(client, secret)
        }
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
        progress.updateAndGet { it?.copy(count = it.count + 1) }
        return slots.getAndUpdate { if (it < MOD_HANDOVER_CAPACITY) it + 1 else it } < MOD_HANDOVER_CAPACITY
    }

    fun leave(starts: Collection<ModPendingSession> = emptyList()) {
        ready.complete(starts)
    }

    suspend fun finish(unconfirmedWrites: Long = 0L) {
        progress.updateAndGet { it?.copy(count = it.count + unconfirmedWrites) }
        queued.close()
        var told = false
        try {
            if (withTimeoutOrNull(HANDOVER_FLUSH_MS) { worker.join() } == null) worker.cancel()
            val missing = progress.get() ?: return
            told = missing.count <= 0 || report(missing)
        } finally {
            // The count is taken only here, after the report was confirmed or has failed: a stop that cancels the wait
            // or the POST must not erase it, so what the successor was not told goes to the log. One report only, after
            // the writer is gone: a second one could count the same events twice.
            worker.cancel()
            val missing = progress.getAndSet(null)?.count ?: 0
            if (!told && missing > 0) {
                val why = lastFailure ?: "the daemon stop ended the flush"
                log("[mods] $missing events were not delivered across the handover: $why\n")
            }
        }
    }

    /** True only when the successor answered that it recorded [count]. A successor that never opened cannot. */
    private suspend fun report(missing: RelayProgress): Boolean {
        if (!ready.isCompleted) return false
        val recorded = withTimeoutOrNull(HANDOVER_REPORT_MS) {
            HttpClient(Java) { followRedirects = false }.use { client ->
                val loss = ModHandoverBatch(emptyList(), missing.count, sender, ordinal, uncertain = missing.pending)
                post(client, key.get(), loss)
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
            val sealed = ArrayList<ModDelivery>(events.size)
            var handled = 0
            for (event in events) {
                // A retired history read can leave a start which could not be decided. It is a loss, not an opt-out.
                if (event.event.unresolved) continue
                val one = event.sealed()
                if (one != null) sealed.add(one) else handled++
            }
            progress.updateAndGet { it?.copy(count = it.count - handled) }
            if (sealed.isNotEmpty()) deliver(client, key, ModHandoverBatch(sealed))
        }
    }

    private suspend fun confirmStarts(client: HttpClient, key: String, pending: Collection<ModPendingSession>) {
        for (chunk in pending.asSequence().mapNotNull { it.entry }.chunked(MOD_HANDOVER_BATCH_SIZE)) {
            deliver(client, key, ModHandoverBatch(emptyList(), starts = chunk))
        }
        val last = ModHandoverBatch(
            emptyList(),
            startsComplete = true,
            accepted = accepted.ordinals,
        )
        deliver(client, key, last)
    }

    private suspend fun deliver(client: HttpClient, key: String, batch: ModHandoverBatch) {
        val numbered = batch.copy(sender = sender, ordinal = ordinal++)
        val receipt = ModHandoverReceipt(numbered.ordinal, batch.events.size)
        progress.updateAndGet { it?.copy(pending = receipt) }
        while (withTimeoutOrNull(HANDOVER_ATTEMPT_MS) { post(client, key, aged(numbered)) } != true) {
            if (!waitingForSuccessor) {
                log("[mods] waiting for the successor to accept the handover\n")
                waitingForSuccessor = true
            }
            waiter.wait(HANDOVER_RETRY_MS)
        }
        progress.updateAndGet { it?.copy(count = it.count - receipt.count, pending = null) }
    }

    private fun aged(batch: ModHandoverBatch): ModHandoverBatch =
        if (batch.startsComplete) batch.copy(acceptedAgeMs = accepted.ages()) else batch

    private suspend fun nextBatch(): List<ModDelivery>? {
        val first = prefix.removeFirstOrNull() ?: queued.receiveCatching().getOrNull() ?: return null
        return buildList {
            add(first)
            while (size < MOD_HANDOVER_BATCH_SIZE) {
                add(prefix.removeFirstOrNull() ?: queued.tryReceive().getOrNull() ?: break)
            }
        }
    }

    private data class RelayProgress(val count: Long, val pending: ModHandoverReceipt? = null)

    private suspend fun post(client: HttpClient, key: String, batch: ModHandoverBatch): Boolean =
        Cancellables.runCatchingCancellable {
            client.post(url) {
                bearerAuth(key)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(batch))
            }.status == HttpStatusCode.NoContent
        }.onFailure { lastFailure = SafeFailureText.render(it) }.getOrDefault(false)
}

package splice.app.mods.run

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandover
import splice.core.util.WallClock
import java.io.OutputStream

private const val REPLY_CAPACITY = 16 // why: session calls share one writer and cannot grow unbounded

internal class ModPipe(
    private val drops: ModDrops,
    private val log: ModLog,
    private val clock: WallClock,
    transferLoss: ModHandoverLoss? = null,
) {
    private val queue = ModQueue(drops, transferLoss)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val replies = Channel<Pair<Long, JsonObject>>(REPLY_CAPACITY)

    @Volatile private var reason: String? = null

    @Volatile private var handover: ModHandover? = null

    val pending: Boolean get() = queue.pending
    val writingSince: Long get() = queue.writingSince

    fun offer(delivery: ModDelivery) {
        if (!queue.subscribes(delivery.event.name)) return
        if (queue.offer(delivery)) {
            wake.trySend(Unit)
        } else {
            val forwarder = handover
            if (forwarder == null) queue.drop(delivery) else forwarder.offer(delivery)
        }
    }

    fun handOver(forwarder: ModHandover) {
        handover = forwarder
        queue.handOver().forEach(forwarder::prefix)
    }

    fun loaded(names: Set<String>) {
        queue.subscriptions = names
        wake.trySend(Unit)
    }

    fun reply(id: Long, value: JsonObject) {
        if (replies.trySend(id to value).isFailure) drops.transport()
        wake.trySend(Unit)
    }

    fun handled() {
        queue.handled()
        wake.trySend(Unit)
    }

    fun finish(why: String) {
        reason = why
        wake.trySend(Unit)
    }

    fun close() {
        queue.close()
        replies.close()
        wake.close()
    }

    suspend fun write(output: OutputStream, calls: ModCalls) {
        output.use { stream ->
            if (reason != null) return
            ModLine.write(stream, buildJsonObject { put("type", "start") })
            while (reason == null) {
                log.reportDrops()
                if (!writeAvailable(stream, calls) && wake.receiveCatching().isClosed) return
            }
            ModLine.write(
                stream,
                buildJsonObject {
                    put("type", "stop")
                    put("reason", reason)
                },
            )
        }
    }

    private fun writeAvailable(stream: OutputStream, calls: ModCalls): Boolean =
        writeReply(stream, calls) || (reason == null && writeEvent(stream))

    private fun writeReply(stream: OutputStream, calls: ModCalls): Boolean {
        val (id, reply) = replies.tryReceive().getOrNull() ?: return false
        if (!ModLine.write(stream, reply)) {
            drops.transport()
            ModLine.write(stream, calls.error(id, "The session list exceeds the mod pipe limit."))
        }
        return true
    }

    private fun writeEvent(stream: OutputStream): Boolean {
        val names = queue.subscriptions?.takeIf { queue.writingSince == 0L } ?: return false
        val pending = queue.take(clock()) ?: return false
        val event = pending.takeIf { it.event.name in names }?.sealed()?.event
        if (event == null) {
            queue.handled()
        } else if (!ModLine.write(stream, event.message())) {
            queue.drop(pending)
            queue.handled()
        }
        return true
    }
}

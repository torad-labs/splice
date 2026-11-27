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

internal class ModPipe(private val drops: ModDrops, private val log: ModLog, private val clock: WallClock) {
    private val queue = ModQueue(drops)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val replies = Channel<Pair<Long, JsonObject>>(REPLY_CAPACITY)

    @Volatile private var reason: String? = null

    @Volatile private var handover: ModHandover? = null

    val pending: Boolean get() = queue.pending
    val writingSince: Long get() = queue.writingSince

    fun offer(delivery: ModDelivery) {
        if (queue.offer(delivery)) {
            wake.trySend(Unit)
        } else {
            handover?.offer(delivery)
        }
    }

    fun handOver(forwarder: ModHandover) {
        handover = forwarder
        queue.handOver().forEach(forwarder::prefix)
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
            ModLine.write(stream, buildJsonObject { put("type", "start") })
            while (reason == null || queue.writingSince != 0L) {
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
        if (queue.writingSince != 0L) return false
        val event = queue.take() ?: return false
        queue.writing(clock())
        if (!ModLine.write(stream, event.message())) {
            drops.event(event.name)
            queue.handled()
        }
        return true
    }
}

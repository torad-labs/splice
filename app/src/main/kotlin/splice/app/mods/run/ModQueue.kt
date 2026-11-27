package splice.app.mods.run

import kotlinx.coroutines.channels.Channel
import splice.app.mods.handover.ModDelivery
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

private const val EVENT_CAPACITY = 2048 // why: a slow mod keeps a bounded prefix
private const val EDGE_RESERVE = 512 // why: stream floods must not consume room for session and turn edges

internal class ModDrops {
    private val events = ConcurrentHashMap<String, LongAdder>()
    private val transport = LongAdder()
    private val log = LongAdder()

    fun register(names: Set<String>) {
        for (name in names) events.computeIfAbsent(name) { LongAdder() }
    }

    fun event(name: String) { events[name]?.increment() }
    fun transport(count: Long = 1) { transport.add(count) }
    fun log(count: Long = 1) { log.add(count) }
    fun byEvent(): Map<String, Long> = events.mapValues { it.value.sum() }.filterValues { it > 0 }
    fun transportCount(): Long = transport.sum()
    fun logCount(): Long = log.sum()
}

internal fun interface ModDeliveryLoss {
    fun lost()
}

internal class ModQueue(private val drops: ModDrops, private val transferLoss: ModDeliveryLoss?) {
    private val events = Channel<ModDelivery>(EVENT_CAPACITY)
    private var transferred = false

    @Volatile private var closed = false

    private val used = AtomicInteger()
    private val inFlight = AtomicReference<Flight?>()
    private val highVolume = setOf("stream.event", "request.send", "response.receive")

    val pending: Boolean get() = used.get() != 0
    val writingSince: Long get() = inFlight.get()?.at ?: 0

    /** Only an atomic reservation and a trySend: no lock, serialization or I/O on the producer. */
    fun offer(delivery: ModDelivery): Boolean {
        val name = delivery.event.name
        val bound = if (name in highVolume) EVENT_CAPACITY - EDGE_RESERVE else EVENT_CAPACITY
        val reserved = used.getAndUpdate { if (it < bound) it + 1 else it } < bound
        val sent = if (reserved) events.trySend(delivery) else null
        if (sent?.isSuccess == true) return true
        if (reserved) used.decrementAndGet()
        if (sent?.isClosed == true || closed) return false
        drop(delivery)
        return true
    }

    @Synchronized
    fun take(at: Long): ModDelivery? {
        if (transferred) return null
        return poll()?.also { inFlight.set(Flight(it, at)) }
    }

    @Synchronized
    fun handOver(): List<ModDelivery> {
        transferred = true
        closeOffers()
        return buildList {
            while (true) add(poll() ?: break)
        }
    }

    private fun poll(): ModDelivery? = events.tryReceive().getOrNull()?.also { used.decrementAndGet() }

    fun handled() { inFlight.set(null) }

    fun drop(delivery: ModDelivery) {
        drops.event(delivery.event.name)
        if (delivery.received) transferLoss?.lost()
    }

    @Synchronized
    fun close() {
        closeOffers()
        inFlight.getAndSet(null)?.delivery?.let(::drop)
        while (true) drop(poll() ?: break)
    }

    private data class Flight(val delivery: ModDelivery, val at: Long)

    private fun closeOffers() {
        events.close()
        closed = true
    }
}

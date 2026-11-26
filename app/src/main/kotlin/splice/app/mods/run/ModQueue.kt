package splice.app.mods.run

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

internal class ModQueue(private val drops: ModDrops) {
    private val events = Channel<ModEvent>(EVENT_CAPACITY)
    private val used = AtomicInteger()
    private val inFlight = AtomicLong()
    private val highVolume = setOf("stream.event", "request.send", "response.receive")

    val pending: Boolean get() = used.get() != 0
    val writingSince: Long get() = inFlight.get()

    /** Only an atomic reservation and a trySend: no lock, serialization or I/O on the producer. */
    fun offer(event: ModEvent): Boolean {
        val bound = if (event.name in highVolume) EVENT_CAPACITY - EDGE_RESERVE else EVENT_CAPACITY
        val reserved = used.getAndUpdate { if (it < bound) it + 1 else it } < bound
        if (reserved && events.trySend(event).isSuccess) return true
        if (reserved) used.decrementAndGet()
        drops.event(event.name)
        return false
    }

    fun take(): ModEvent? = events.tryReceive().getOrNull()?.also { used.decrementAndGet() }

    fun writing(at: Long) { inFlight.set(at) }
    fun handled() { inFlight.set(0) }

    fun close() {
        events.close()
        while (true) drops.event(take()?.name ?: break)
    }
}

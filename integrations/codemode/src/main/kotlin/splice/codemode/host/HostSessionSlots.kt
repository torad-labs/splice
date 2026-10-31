// NEW: only slot reservation and publication take the host lock; reader lookups never wait on native work.
package splice.codemode.host

import org.graalvm.polyglot.Engine
import splice.codemode.CodeModeHeap
import splice.codemode.engine.HostWorkerSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class HostSessionSlot {
    val started = AtomicBoolean()
    val opened = CompletableFuture<HostWorkerSession?>()

    @Volatile var closing = false

    @Volatile var session: HostWorkerSession? = null

    fun end() {
        closing = true
    }
}

internal class HostSessionSlots {
    private val lock = ReentrantLock()
    private val slots = ConcurrentHashMap<Long, HostSessionSlot>()
    private val ended = mutableSetOf<Long>()
    private var closed = false

    fun reserve(id: Long): HostSessionSlot? = lock.withLock {
        if (closed || id in ended) return null
        slots[id]?.let { return it }
        if (slots.size >= CodeModeHeap.maxEnginesPerHost) return null
        HostSessionSlot().also { slots[id] = it }
    }

    fun publish(slot: HostSessionSlot, session: HostWorkerSession): Boolean = lock.withLock {
        if (closed || slot.closing) return false
        slot.session = session
        true
    }

    fun end(id: Long): HostSessionSlot? = lock.withLock {
        ended.add(id)
        slots[id]?.also(HostSessionSlot::end)
    }

    fun gone(id: Long, slot: HostSessionSlot) {
        lock.withLock { slots.remove(id, slot) }
    }

    fun engine(id: Long): Engine? = slots[id]?.session?.engine

    fun execute(id: Long, task: Runnable): Boolean = slots[id]?.session?.execute(task) == true

    fun count(): Int = slots.values.mapNotNull { it.session?.engine }.toSet().size

    fun shutdown(): List<HostSessionSlot> = lock.withLock {
        closed = true
        slots.values.toList().onEach(HostSessionSlot::end)
    }
}

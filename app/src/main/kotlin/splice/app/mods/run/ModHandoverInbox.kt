package splice.app.mods.run

import splice.app.mods.handover.MOD_HANDOVER_CAPACITY
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandover

/** A mod may still be waiting for its predecessor. Other mods need not wait with it. */
internal class ModHandoverInbox(private val mod: String) {
    private val pending = ArrayDeque<ModDelivery>()
    private var ready: Ready? = null
    private var closed = false

    fun offer(delivery: ModDelivery): Boolean {
        if (closed || !addressed(delivery)) return true
        val target = ready
        val addressed = if (delivery.only == null) delivery.copy(only = mod) else delivery
        return when {
            target != null -> true.also { target.offer(addressed) }
            pending.size < MOD_HANDOVER_CAPACITY -> true.also { pending.addLast(addressed) }
            else -> false
        }
    }

    fun bind(pipe: ModPipe, names: Set<String>) {
        val target = Ready(pipe, names)
        ready = target
        closed = false
        while (true) target.offer(pending.removeFirstOrNull() ?: break)
    }

    fun close(forwarding: ModHandover? = null) {
        closed = true
        if (forwarding != null) {
            ready?.pipe?.handOver(forwarding)
            pending.forEach(forwarding::prefix)
        }
        ready = null
        pending.clear()
    }

    private fun addressed(delivery: ModDelivery): Boolean {
        if (delivery.only != null && delivery.only != mod) return false
        return !delivery.offAll && mod !in delivery.offNames
    }

    private class Ready(val pipe: ModPipe, private val names: Set<String>) {
        fun offer(delivery: ModDelivery) {
            if (delivery.event.name in names) pipe.offer(delivery)
        }
    }
}

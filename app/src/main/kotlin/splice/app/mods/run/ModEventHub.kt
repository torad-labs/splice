package splice.app.mods.run

import kotlinx.serialization.json.JsonObject
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandover
import splice.launch.ModsOff

/** Subscriptions are published off the turn path. A turn only reads an immutable snapshot and offers. */
internal class ModEventHub {
    private val emptyPayload = JsonObject(emptyMap())

    @Volatile private var byEvent = emptyMap<String, List<ModListener>>()

    @Volatile private var retiring: Retiring? = null

    val listening: Boolean get() = retiring?.names?.isNotEmpty() ?: byEvent.isNotEmpty()

    fun listening(name: String): Boolean = listening(name, null)

    fun listening(name: String, modsOff: ModsOff?): Boolean {
        if (modsOff?.all == true) return false
        val handover = retiring
        return if (handover == null) {
            byEvent[name].orEmpty().any { modsOff?.suppresses(it.mod) != true }
        } else {
            handover.names[name].orEmpty().any { modsOff?.suppresses(it) != true }
        }
    }

    fun offer(name: String, modsOff: ModsOff?, projection: ModEventProjection) {
        offer(ModEvent(name, null, emptyPayload, projection), modsOff)
    }

    /** Use the settings of the event's own launch, not the latest launch seen for the session. */
    fun offer(event: ModEvent, modsOff: ModsOff? = null) {
        if (modsOff?.all == true) return
        // Read subscriptions before the mode: a stale local publisher still reaches the switched pipes.
        val local = byEvent[event.name].orEmpty()
        val delivery = ModDelivery(event, offNames = modsOff?.names.orEmpty())
        val handover = retiring
        if (handover != null) {
            if (handover.names[event.name].orEmpty().any { modsOff?.suppresses(it) != true }) {
                handover.forwarder.offer(delivery)
            }
        } else {
            publish(delivery, local)
        }
    }

    private fun publish(delivery: ModDelivery, listeners: List<ModListener>) {
        if (delivery.offAll) return
        for (listener in listeners) {
            if (delivery.only != null && delivery.only != listener.mod) continue
            if (listener.mod !in delivery.offNames) listener.pipe.offer(delivery.copy(only = listener.mod))
        }
    }

    @Synchronized
    fun handOver(forwarder: ModHandover) {
        val snapshot = byEvent
        for (listener in snapshot.values.flatten().toSet()) listener.pipe.handOver(forwarder)
        retiring = Retiring(forwarder, snapshot.mapValues { (_, listeners) -> listeners.map(ModListener::mod) })
    }

    private data class Retiring(val forwarder: ModHandover, val names: Map<String, List<String>>)

    @Synchronized
    fun bind(listener: ModListener, events: Set<String>) {
        byEvent = byEvent.toMutableMap().apply {
            for (event in events) put(event, get(event).orEmpty() + listener)
        }
    }

    @Synchronized
    fun unbind(listener: ModListener) {
        byEvent = byEvent.mapValues { (_, targets) -> targets - listener }.filterValues(List<ModListener>::isNotEmpty)
    }
}

internal data class ModListener(val mod: String, val pipe: ModPipe)

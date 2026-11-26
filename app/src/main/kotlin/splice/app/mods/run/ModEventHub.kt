package splice.app.mods.run

import splice.launch.ModsOff

/** Subscriptions are published off the turn path. A turn only reads an immutable snapshot and offers. */
internal class ModEventHub {
    @Volatile private var byEvent = emptyMap<String, List<ModListener>>()

    val listening: Boolean get() = byEvent.isNotEmpty()

    fun listening(name: String): Boolean = name in byEvent

    /** Use the settings of the Event's own launch, not the latest launch seen for the session. */
    fun offer(event: ModEvent, modsOff: ModsOff? = null) {
        if (modsOff?.all == true) return
        for (listener in byEvent[event.name].orEmpty()) {
            if (modsOff?.suppresses(listener.mod) != true) listener.pipe.offer(event)
        }
    }

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

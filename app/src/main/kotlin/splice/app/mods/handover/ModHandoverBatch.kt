package splice.app.mods.handover

import kotlinx.serialization.Serializable
import splice.app.mods.run.ModEvent

internal const val MOD_HANDOVER_PATH = "/api/mods/handover"
internal const val MOD_HANDOVER_CAPACITY = 4096
internal const val MOD_HANDOVER_BATCH_SIZE = 64
internal const val MOD_HANDOVER_DROPPED = "handoverDropped"

@Serializable
internal data class ModHandoverBatch(val events: List<ModDelivery>, val dropped: Long = 0) {
    fun valid(): Boolean =
        events.size <= MOD_HANDOVER_BATCH_SIZE && dropped >= 0 &&
            events.all { it.event.name in ModHandoverEvents.names }
}

@Serializable
internal data class ModDelivery(
    val event: ModEvent,
    val offAll: Boolean = false,
    val offNames: Set<String> = emptySet(),
    val only: String? = null,
)

internal object ModHandoverEvents {
    val names = setOf(
        "session.start",
        "session.end",
        "turn.start",
        "request.send",
        "response.receive",
        "stream.event",
        "tool.call",
        "turn.complete",
    )
}

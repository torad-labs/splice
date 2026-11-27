package splice.app.mods.handover

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import splice.app.mods.run.ModEvent
import splice.app.mods.sessions.ModSessionEntry

internal const val MOD_HANDOVER_PATH = "/api/mods/handover"
internal const val MOD_HANDOVER_CAPACITY = 4096 // why: bound the backlog while the successor opens its listener
internal const val MOD_HANDOVER_BATCH_SIZE = 64 // why: amortize local HTTP without holding the entire backlog in a body
internal const val MOD_HANDOVER_DROPPED = "handoverDropped"

// why: a retired daemon keeps retrying for up to 15 minutes, so a receipt outlives that by a margin and is never
// dropped for being the seventeenth sender
internal const val MOD_HANDOVER_RECEIPT_MS = 20L * 60 * 1000

// why: only a runaway list could reach this; age, not count, is what ends a receipt
internal const val MOD_HANDOVER_RECEIPT_CAP = 4096

@Serializable
@JsonIgnoreUnknownKeys // An older retiree may still send a wall time; only its ordinal is used.
internal data class ModHandoverBatch(
    val events: List<ModDelivery>,
    val dropped: Long = 0,
    val sender: String? = null,
    val ordinal: Long = 0,
    val starts: List<ModSessionEntry> = emptyList(),
    val startsComplete: Boolean = false,
    val uncertain: ModHandoverReceipt? = null,
    /** The last batch the sending daemon accepted from each earlier sender, so a retry of one is not told twice. */
    val accepted: Map<String, Long> = emptyMap(),
    /** Monotonic elapsed milliseconds since acceptance, measured by the sender at the time of transfer. */
    val acceptedAgeMs: Map<String, Long> = emptyMap(),
) {
    fun valid(): Boolean = bounded() && events.all { it.event.name in ModHandoverEvents.names }

    private fun bounded(): Boolean =
        events.size + starts.size <= MOD_HANDOVER_BATCH_SIZE && (dropped >= 0 && ordinal >= 0) &&
            accepted.size <= MOD_HANDOVER_RECEIPT_CAP && acceptedAgeMs.size <= MOD_HANDOVER_RECEIPT_CAP
}

@Serializable
internal data class ModHandoverReceipt(val ordinal: Long, val count: Int)

@Serializable
internal data class ModDelivery(
    val event: ModEvent,
    val offAll: Boolean = false,
    val offNames: Set<String> = emptySet(),
    val only: String? = null,
    @Transient val received: Boolean = false,
) {
    fun sealed(): ModDelivery? {
        val options = event.audience?.options()
        if (options?.all == true || only?.let { options?.suppresses(it) } == true) return null
        val sealed = event.sealed() ?: return null
        return if (options == null && sealed === event) {
            this
        } else {
            copy(event = sealed, offNames = offNames + options?.names.orEmpty())
        }
    }
}

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

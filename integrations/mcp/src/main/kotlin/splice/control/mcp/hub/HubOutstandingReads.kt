package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Durable exchanges keep their exact generation alive even when the front cannot send a heartbeat. */
internal class HubOutstandingReads {
    fun read(saved: JsonObject): Set<HubDrainHolder>? {
        val value = saved["relay"] ?: return emptySet()
        return (value as? JsonObject)?.let { relay ->
            ledger(relay, "calls")?.let { calls ->
                ledger(relay, "server_requests")?.let { calls + it }
            }
        }
    }

    private fun ledger(relay: JsonObject, name: String): Set<HubDrainHolder>? {
        val value = relay[name] ?: return emptySet()
        val records = value as? JsonObject ?: return null
        return records.values.map { record -> (record as? JsonObject)?.let(::holder) }
            .takeUnless { holders -> holders.any { it == null } }
            ?.filterNotNull()
            ?.toSet()
    }

    private fun holder(record: JsonObject): HubDrainHolder? {
        val socket = JsonScalars.str(record, HubFields.SOCKET)?.takeIf { it.isNotEmpty() }
        val generation = JsonScalars.str(record, HubFields.GENERATION)?.takeIf { it.isNotEmpty() }
        return if (socket != null && generation != null) HubDrainHolder(socket, generation) else null
    }
}

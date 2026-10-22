package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Serializes a holder's durable identity, handshake, and settlement capability without making memory a record builder. */
internal class HolderStateRecord {
    fun record(holder: Holder): JsonObject = buildJsonObject {
        identity(this, holder)
        handshake(this, holder)
        continuity(this, holder)
    }

    private fun identity(out: JsonObjectBuilder, holder: Holder) {
        holder.generation?.let { out.put(GENERATION, it) }
        holder.identity?.let { out.put(IDENTITY, it) }
        holder.init?.let { out.put(INITIALIZE, it) }
    }

    private fun handshake(out: JsonObjectBuilder, holder: Holder) {
        holder.handshakeId?.let { out.put(HANDSHAKE_ID, it) }
        holder.handshakeGeneration?.let { out.put(HANDSHAKE_GENERATION, it) }
        holder.handshakeIdentity?.let { out.put(HANDSHAKE_IDENTITY, it) }
        out.put(LAST_CALL, holder.lastCall)
        out.put(INITIALIZED_PENDING, holder.initializedPending)
        out.put(INITIALIZED_UNCERTAIN, holder.initializedUncertain)
    }

    private fun continuity(out: JsonObjectBuilder, holder: Holder) {
        holder.retirementFence?.let { out.put(RETIREMENT_FENCE, it) }
        holder.exitConfirmedGeneration?.let { out.put(EXIT_CONFIRMED_GENERATION, it) }
        if (holder.reverseResponseRetries != 0) out.put(REVERSE_RESPONSE_RETRIES, holder.reverseResponseRetries)
        if (holder.settlementProtocol) out.put(SETTLEMENT_PROTOCOL, true)
        out.put(RECOVERY_UNCERTAIN, holder.recoveryUncertain)
    }
}

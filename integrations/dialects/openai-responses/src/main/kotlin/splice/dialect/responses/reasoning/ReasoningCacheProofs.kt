package splice.dialect.responses.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonWire

/** Proof metadata consumes serialized bytes, never the logical reasoning-round quota. */
internal class ReasoningCacheProofs {
    private val frameBytes = JsonWire.byteSize(
        Json.encodeToJsonElement(
            StoredRound.serializer(),
            StoredRound(emptyList(), emptyList(), mapOf("" to null)),
        ),
    ) - JsonWire.byteSize(JsonObject(mapOf("" to JsonNull))) + JsonWire.byteSize("\n")

    fun restore(conversation: ReasoningCacheConvo, rounds: List<StoredRound>) {
        rounds.forEach { merge(conversation, it.resultProofs, Long.MAX_VALUE) }
    }

    /** New evidence may miss under byte pressure. Conflicting existing evidence becomes ambiguous. */
    fun merge(
        conversation: ReasoningCacheConvo,
        offered: Map<String, String?>,
        availableBytes: Long,
    ): ReasoningProofUpdate {
        var room = availableBytes
        var bytes = 0L
        var changed = false
        offered.forEach { (key, observed) ->
            val present = conversation.resultProofs.containsKey(key)
            val previous = conversation.resultProofs[key]
            val next = if (present && previous != observed) null else observed
            if (!present || previous != next) {
                val growth = growth(conversation, key, next, present)
                if (growth <= room) {
                    conversation.resultProofs[key] = next
                    room -= growth
                    bytes += growth
                    changed = true
                }
            }
        }
        conversation.proofBytes += bytes
        conversation.bytes += bytes
        return ReasoningProofUpdate(changed, bytes)
    }

    private fun growth(conversation: ReasoningCacheConvo, key: String, digest: String?, present: Boolean): Long {
        val encoded = entryBytes(key, digest)
        return when {
            present -> encoded - entryBytes(key, conversation.resultProofs[key])
            conversation.resultProofs.isEmpty() -> frameBytes + encoded
            else -> encoded - ",".length
        }
    }

    private fun entryBytes(key: String, digest: String?): Long = JsonWire.byteSize(
        JsonObject(mapOf(key to (digest?.let(::JsonPrimitive) ?: JsonNull))),
    )
}

internal data class ReasoningProofUpdate(val changed: Boolean, val bytes: Long)

/** One atomic read owns both the encrypted items and the proofs which permit exact marker recovery. */
internal data class ReasoningCacheView(
    val rounds: Map<String, List<String>>,
    val proofs: Map<String, String?> = emptyMap(),
)

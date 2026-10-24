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
        rounds.filter { it.resultProofs.isNotEmpty() }.forEach { round ->
            round.resultProofs.forEach { (key, observed) ->
                val present = conversation.resultProofs.containsKey(key)
                val previous = conversation.resultProofs[key]
                conversation.resultProofs[key] = if (present && previous != observed) null else observed
            }
            conversation.proofBytes += recordBytes(round.resultProofs)
        }
        conversation.proofBytes += conversation.resultProofs.filterValues { it != null }.keys.sumOf(::ambiguityBytes)
        conversation.bytes += conversation.proofBytes
    }

    /** New evidence may miss under byte pressure. Conflicting existing evidence becomes ambiguous. */
    fun merge(
        conversation: ReasoningCacheConvo,
        offered: Map<String, String?>,
        availableBytes: Long,
    ): ReasoningProofUpdate {
        var room = availableBytes
        var bytes = 0L
        val delta = linkedMapOf<String, String?>()
        offered.forEach { (key, observed) ->
            val present = conversation.resultProofs.containsKey(key)
            val previous = conversation.resultProofs[key]
            val next = if (present && previous != observed) null else observed
            if (!present || previous != next) {
                val growth = growth(
                    conversation,
                    delta,
                    key,
                    next,
                    present,
                ) + reserveChange(key, present, previous, next)
                if (growth <= room) {
                    conversation.resultProofs[key] = next
                    room -= growth
                    bytes += growth
                    delta[key] = next
                }
            }
        }
        conversation.proofBytes += bytes
        conversation.bytes += bytes
        return ReasoningProofUpdate(delta, bytes)
    }

    /** Only an actual index migration compacts the journal; its released bytes return to the same budget. */
    fun compact(conversation: ReasoningCacheConvo): Long {
        val retained = if (conversation.resultProofs.isEmpty()) 0L else recordBytes(conversation.resultProofs)
        val reserved = conversation.resultProofs.filterValues { it != null }.keys.sumOf(::ambiguityBytes)
        val difference = retained + reserved - conversation.proofBytes
        conversation.proofBytes += difference
        conversation.bytes += difference
        return difference
    }

    private fun growth(
        conversation: ReasoningCacheConvo,
        delta: Map<String, String?>,
        key: String,
        digest: String?,
        present: Boolean,
    ): Long {
        val encoded = entryBytes(key, digest)
        return when {
            conversation.rounds.isNotEmpty() -> if (delta.isEmpty()) frameBytes + encoded else encoded - ",".length
            present -> encoded - entryBytes(key, conversation.resultProofs[key])
            conversation.resultProofs.isEmpty() -> frameBytes + encoded
            else -> encoded - ",".length
        }
    }

    private fun ambiguityBytes(key: String): Long = recordBytes(mapOf(key to null))

    private fun reserveChange(key: String, present: Boolean, previous: String?, next: String?): Long = when {
        !present && next != null -> ambiguityBytes(key)
        previous != null && next == null -> -ambiguityBytes(key)
        else -> 0L
    }

    private fun recordBytes(proofs: Map<String, String?>): Long = JsonWire.byteSize(
        Json.encodeToJsonElement(StoredRound.serializer(), StoredRound(emptyList(), emptyList(), proofs)),
    ) + JsonWire.byteSize("\n")

    private fun entryBytes(key: String, digest: String?): Long = JsonWire.byteSize(
        JsonObject(mapOf(key to (digest?.let(::JsonPrimitive) ?: JsonNull))),
    )
}

internal data class ReasoningProofUpdate(val delta: Map<String, String?>, val bytes: Long) {
    val changed: Boolean get() = delta.isNotEmpty()
}

/** One atomic read owns both the encrypted items and the proofs which permit exact marker recovery. */
internal data class ReasoningCacheView(
    val rounds: Map<String, List<String>>,
    val proofs: Map<String, String?> = emptyMap(),
)

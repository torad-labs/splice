package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars

/** Only an accepted callback and its unchanged scoped prefix can authorize a cleared placeholder. */
internal object CodeModeClearedHistory {
    fun restore(
        received: List<JsonElement>,
        entry: CodeModeLedgerEntry,
        proof: CodeModeCallbackProof,
    ): List<JsonElement> {
        if (received.none { cleared(it as? JsonObject) }) return received
        val outputs = CodeModeCallbackReceipts.items(entry.covered, "function_call_output")
        val calls = CodeModeCallbackReceipts.items(received, "function_call")
        val actual = CodeModeCallbackReceipts.items(received, "function_call_output")
        return received.flatMapIndexed { index, item ->
            val value = item as? JsonObject
            val id = JsonScalars.str(value, "call_id").orEmpty()
            val original = outputs[id]?.singleOrNull()
            val receipt = entry.callbacks[id]
            val durable = authorized(CodeModeClearedCandidate(id, calls[id], actual[id], original, receipt), proof)
            val accepted = cleared(value) && (id in proof.verified || durable)
            if (accepted && original != null) {
                val media = receipt?.media.orEmpty()
                val present = received.drop(index + 1).take(media.size) == media
                listOf(original) + if (present) emptyList() else media
            } else {
                listOf(item)
            }
        }
    }

    private fun authorized(candidate: CodeModeClearedCandidate, proof: CodeModeCallbackProof): Boolean {
        val receipt = candidate.receipt ?: return false
        val original = candidate.original
        val call = candidate.calls?.singleOrNull()
        val available = original != null && call != null && candidate.id in proof.candidates
        if (!available) return false
        val identity = InputDigest.hex(checkNotNull(call)) == receipt.callDigest &&
            InputDigest.hex(checkNotNull(original)) == receipt.outputDigest
        val result = proof.errors[candidate.id] == receipt.isError && candidate.outputs?.size == 1
        return identity && result
    }

    private fun cleared(item: JsonObject?): Boolean = JsonScalars.str(item, "type") == "function_call_output" &&
        JsonScalars.str(item, "output") == "[Old tool result content cleared]"
}

private data class CodeModeClearedCandidate(
    val id: String,
    val calls: List<JsonObject>?,
    val outputs: List<JsonObject>?,
    val original: JsonObject?,
    val receipt: CodeModeCallbackReceipt?,
)

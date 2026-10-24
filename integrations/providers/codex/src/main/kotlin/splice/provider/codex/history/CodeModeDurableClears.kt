package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import splice.core.perf.InputDigest
import splice.provider.codex.CodeModeRunContext

/** Scoped callback identity and a complete acknowledged prefix replace expired execution-record proofs. */
internal class CodeModeDurableClears(
    private val ledger: CodeModeUpstreamLedger,
    private val delta: CodeModeClientDelta,
) {
    fun acknowledge(context: CodeModeRunContext, conversation: List<JsonElement>) {
        if (context.turn.rendering.clearedCallbacks.isEmpty()) return
        val proof = CodeModeCallbackReceipts.proof(context)
        val proved = ledger.entries(context.key).firstNotNullOfOrNull { entry -> proven(entry, conversation, proof) }
        if (!proved.isNullOrEmpty()) {
            context.turn.rendering.verifiedCleared.getOrPut("authoritative-history") { mutableSetOf() }.addAll(proved)
        }
    }

    private fun proven(
        entry: CodeModeLedgerEntry,
        conversation: List<JsonElement>,
        proof: CodeModeCallbackProof,
    ): Set<String>? {
        if (delta.after(entry, conversation, proof) == null) return null
        val normalized = delta.normalized(entry, conversation, proof)
        val outputs = CodeModeCallbackReceipts.items(normalized, "function_call_output")
        return proof.candidates.filter { id ->
            val output = outputs[id]?.singleOrNull()
            val receipt = entry.callbacks[id]
            val actual = output?.let(InputDigest::hex)
            actual != null && actual == receipt?.outputDigest && proof.errors[id] == receipt.isError
        }.toSet()
    }
}

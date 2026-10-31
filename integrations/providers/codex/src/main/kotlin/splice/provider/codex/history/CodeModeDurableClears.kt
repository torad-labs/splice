package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.branch.CodeModeClientOutputDigests

/** Scoped callback identity and a complete acknowledged prefix replace expired execution-record proofs. */
internal class CodeModeDurableClears(
    private val ledger: CodeModeUpstreamLedger,
    private val delta: CodeModeClientDelta,
) {
    fun acknowledge(
        context: CodeModeRunContext,
        conversation: List<JsonElement>,
        records: List<CodeModeRecord> = emptyList(),
    ) {
        if (context.turn.rendering.clearedCallbacks.isEmpty()) return
        val proof = CodeModeCallbackReceipts.proof(context)
        val (entry, proved) = ledger.entries(context.key).firstNotNullOfOrNull { entry ->
            proven(entry, conversation, proof)?.let { entry to it }
        } ?: return
        if (proved.isEmpty()) return
        context.turn.rendering.verifiedCleared.getOrPut("authoritative-history") { mutableSetOf() }.addAll(proved)
        bind(context, conversation, records, entry, proved)
    }

    /** A ledger receipt proves one accepted wire result, never every record that happens to share its id. */
    private fun bind(
        context: CodeModeRunContext,
        conversation: List<JsonElement>,
        records: List<CodeModeRecord>,
        entry: CodeModeLedgerEntry,
        proved: Set<String>,
    ) {
        val calls = CodeModeCallbackReceipts.items(conversation, "function_call")
        val outputs = CodeModeCallbackReceipts.items(conversation, "function_call_output")
        val owners = records.flatMap { it.clientIds() }.groupingBy { it }.eachCount()
        records.filter { it.key == context.key && it.sessionId == context.turn.sessionId }.forEach { record ->
            val own = proved.filter { id ->
                val output = outputs[id]?.singleOrNull()
                val receipt = entry.callbacks[id]
                calls[id]?.size == 1 && owners[id] == 1 && output != null && receipt != null &&
                    matches(record, id, output, receipt, context)
            }
            if (own.isNotEmpty()) {
                context.turn.rendering.verifiedCleared.getOrPut(record.id) { mutableSetOf() }.addAll(own)
            }
        }
    }

    private fun matches(
        record: CodeModeRecord,
        id: String,
        output: JsonObject,
        receipt: CodeModeCallbackReceipt,
        context: CodeModeRunContext,
    ): Boolean {
        val accepted = record.results[id]
        val candidate = context.turn.rendering.clearedCallbacks[id]
        if (accepted == null || candidate == null) return false
        val call = record.issued.flatMap { it.calls }.filter { it.clientId == id }
            .distinct().singleOrNull() ?: return false
        val identity = call.exposed && call.name == candidate.name && call.arguments == candidate.arguments
        val cleared = JsonScalars.str(output, "output") == "[Old tool result content cleared]"
        val digest = CodeModeClientOutputDigests.replay(record.accepted.clientOutputDigest(id), output, accepted.output)
        return identity && cleared && accepted.isError == receipt.isError && digest == receipt.outputDigest
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

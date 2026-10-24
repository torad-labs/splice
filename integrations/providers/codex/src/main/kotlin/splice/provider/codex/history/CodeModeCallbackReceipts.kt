package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.provider.codex.CodeModeRunContext

/** Proof retained independently of execution-record TTL. The full original callback remains in the client prefix. */
@Serializable
internal data class CodeModeCallbackReceipt(
    val callDigest: String,
    val outputDigest: String,
    val media: List<JsonElement>,
    val isError: Boolean,
)

internal data class CodeModeCallbackProof(
    val verified: Set<String> = emptySet(),
    val candidates: Set<String> = emptySet(),
    val errors: Map<String, Boolean> = emptyMap(),
)

internal object CodeModeCallbackReceipts {
    fun proof(context: CodeModeRunContext): CodeModeCallbackProof = CodeModeCallbackProof(
        context.turn.rendering.verifiedCleared.values.flatten().toSet(),
        context.turn.rendering.clearedCallbacks.keys,
        context.turn.toolResults.groupBy { it.id }.mapNotNull { (id, results) ->
            results.singleOrNull()?.let { id to it.isError }
        }.toMap(),
    )

    fun capture(
        context: CodeModeRunContext,
        covered: List<JsonElement>,
        inherited: Map<String, CodeModeCallbackReceipt>,
    ): Map<String, CodeModeCallbackReceipt> {
        val calls = items(covered, "function_call")
        val outputs = items(covered, "function_call_output")
        val results = context.turn.toolResults.groupBy { it.id }
        return calls.mapNotNull { (id, values) ->
            val call = values.singleOrNull()
            val output = outputs[id]?.singleOrNull()
            val result = results[id]?.singleOrNull()
            val complete = call != null && output != null && result != null
            if (!complete) return@mapNotNull null
            val prior = inherited[id]
            val media = if (id in context.turn.rendering.clearedCallbacks && prior != null) {
                prior.media
            } else {
                context.turn.rendering.media[id].orEmpty()
            }
            id to CodeModeCallbackReceipt(
                InputDigest.hex(checkNotNull(call)),
                InputDigest.hex(checkNotNull(output)),
                media,
                checkNotNull(result).isError,
            )
        }.toMap()
    }

    fun items(input: List<JsonElement>, type: String): Map<String, List<JsonObject>> =
        input.filterIsInstance<JsonObject>().filter { JsonScalars.str(it, "type") == type }
            .groupBy { JsonScalars.str(it, "call_id").orEmpty() }
}

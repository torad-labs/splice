package splice.provider.codex.branch

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.provider.codex.CodeModeRunContext

/** One exact wire-item hash per accepted callback, persisted with its existing result and budget. */
internal object CodeModeClientOutputDigests {
    // why: v1 hashes the complete canonical function_call_output item, including call_id and all fields.
    private const val CLIENT_OUTPUT_DIGEST_VERSION = "v1:"
    private val known = Regex("v1:[0-9a-f]{64}")

    fun capture(context: CodeModeRunContext, ids: Set<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val items = context.client?.request?.second.orEmpty().filterIsInstance<JsonObject>()
        val calls = items.filter { text(it, "type") == "function_call" }.groupingBy { text(it, "call_id") }.eachCount()
        return items.filter { text(it, "type") == "function_call_output" }.groupBy { text(it, "call_id") }
            .mapNotNull { (id, outputs) ->
                val output = outputs.singleOrNull()
                if (id !in ids || calls[id] != 1) return@mapNotNull null
                output?.let { id to CLIENT_OUTPUT_DIGEST_VERSION + InputDigest.hex(it) }
            }.toMap()
    }

    /** Missing legacy metadata can still prove text-only prefixes. Unknown versions honestly miss. */
    fun replay(saved: String?, item: JsonObject, acceptedOutput: String): String? = when {
        saved == null -> InputDigest.hex(JsonObject(item + ("output" to JsonPrimitive(acceptedOutput))))
        known.matches(saved) -> saved.removePrefix(CLIENT_OUTPUT_DIGEST_VERSION)
        else -> null
    }

    private fun text(item: JsonObject, key: String): String = JsonScalars.str(item, key).orEmpty()
}

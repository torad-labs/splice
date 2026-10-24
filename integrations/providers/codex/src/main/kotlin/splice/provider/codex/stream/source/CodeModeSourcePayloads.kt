package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeWeight.STORED
import java.util.IdentityHashMap

/** Raw receipts can outlive the reader while a detached sink still holds prose to deliver. */
internal class CodeModeSourcePayloads(private val registry: CodexCodeModeRegistry) {
    private var weights = IdentityHashMap<JsonObject, Long>()
    private var retainedBytes = 0L

    fun retain(record: CodeModeRecord, items: List<JsonObject>?): Boolean {
        if (items == null) return true
        val next = IdentityHashMap<JsonObject, Long>()
        val bytes = items.sumOf { item ->
            val weight = weights[item] ?: STORED.json(item)
            next[item] = weight
            weight
        }
        if (!registry.source.holdPayload(record, (bytes - retainedBytes).coerceAtLeast(0L))) return false
        weights = next
        retainedBytes = maxOf(retainedBytes, bytes)
        return true
    }
}

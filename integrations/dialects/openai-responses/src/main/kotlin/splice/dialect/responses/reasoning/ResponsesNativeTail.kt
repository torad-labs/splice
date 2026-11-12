package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Terminal additions require the owned raw exec and every confirmed tail item in delivery order. */
internal class ResponsesNativeTail {
    private var anchor: JsonObject? = null

    fun seal(call: JsonObject) {
        anchor = call
    }

    fun reconcile(
        output: List<JsonObject>,
        observed: List<JsonObject>?,
        prefix: List<JsonObject>,
    ): NativeTailUpdate? {
        val source = anchor ?: return null
        val at = output.indices.singleOrNull { output[it] == source } ?: return null
        val before = output.take(at).filter {
            JsonScalars.str(it, "type") in setOf(NATIVE_MESSAGE_TYPE, "reasoning")
        }
        return observed?.takeIf { prefixConfirmed(before, prefix) }?.let { prove(output.drop(at + 1), it, at + 1) }
    }

    private fun prove(terminal: List<JsonObject>, observed: List<JsonObject>, offset: Int): NativeTailUpdate? {
        val matched = matches(terminal, observed)
        val added = terminal.mapIndexedNotNull { index, item ->
            if (matched[index] == null) IndexedValue(offset + index, item) else null
        }
        return when {
            matched.filterNotNull() != observed -> null
            terminal.map(::identity).distinct().size != terminal.size -> null
            !added.all { encryptedReasoning(it.value) } -> null
            else -> NativeTailUpdate(terminal.mapIndexed { index, item -> matched[index] ?: item }, added)
        }
    }

    private fun prefixConfirmed(terminal: List<JsonObject>, prefix: List<JsonObject>): Boolean {
        val matched = matches(terminal, prefix)
        if (matched.any { it == null }) return false
        val selected = matched.filterNotNull().toHashSet()
        return prefix.filter { it in selected } == matched
    }

    private fun matches(terminal: List<JsonObject>, observed: List<JsonObject>): List<JsonObject?> {
        val known = observed.associateBy(::identity)
        val anonymous = anonymousMatches(observed, terminal)
        return terminal.map { candidate ->
            known[
                identity(
                    candidate,
                ),
            ]?.takeIf { it == candidate } ?: anonymous[candidate]
        }
    }

    private fun anonymousMatches(observed: List<JsonObject>, terminal: List<JsonObject>): Map<JsonObject, JsonObject> {
        val anonymous = observed.filter { JsonScalars.str(it, "id") == null }.groupBy(::messageKey)
        val candidates = terminal.groupBy(::messageKey)
        return anonymous.filterKeys { it != null }.filterValues { it.size == 1 }.mapNotNull { (key, entries) ->
            candidates[key]?.singleOrNull()?.let { it to entries.single() }
        }.toMap()
    }

    private fun messageKey(item: JsonObject): String? =
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE && JsonScalars.str(item, "role") == "assistant") {
            ResponsesMessageText.text(item).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    private fun identity(item: JsonObject): JsonElement = item["id"] ?: item

    private fun encryptedReasoning(item: JsonObject): Boolean {
        if (JsonScalars.str(item, "type") != "reasoning") return false
        return JsonScalars.str(item, "id") != null && !JsonScalars.str(item, "encrypted_content").isNullOrEmpty()
    }
}

internal data class NativeTailUpdate(val items: List<JsonObject>, val added: List<IndexedValue<JsonObject>>)

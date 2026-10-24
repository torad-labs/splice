package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

/** Item completion owns history. Sparse terminal arrays cannot erase already completed items. */
internal class ResponsesCompletedOutput {
    private val items = mutableListOf<IndexedValue<JsonObject>>()
    private val positions = mutableMapOf<String, Int>()
    var retainedChars: Long = 0
        private set

    fun collect(item: JsonObject?, index: Int?, enabled: Boolean = true) {
        if (item == null || !enabled) return
        val id = identity(item)
        val known = id?.let(positions::get)
        val at = known ?: index ?: ((items.maxOfOrNull { it.index } ?: -1) + 1)
        if (known != null) return
        items += IndexedValue(at, item)
        if (JsonScalars.str(item, "type") !in setOf("reasoning", "message")) {
            retainedChars += JsonWire.string(item).length
        }
        if (id != null) positions[id] = at
    }

    fun snapshot(terminal: JsonObject?): List<JsonObject> {
        val output = (terminal?.get("output") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val observed = items.sortedBy { it.index }.map { it.value }
        if (output.isEmpty()) return observed
        val identities = observed.map(::identity)
        val retained = output.filter { identity(it) in identities }
        // A complete terminal is allowed to fill missing items only while preserving observed order.
        if (retained.map(::identity) == identities && identities.none { it == null }) {
            val completed = observed.associateBy(::identity)
            return output.map { completed[identity(it)] ?: it }
        }
        return observed + output.filter { identity(it)?.let(positions::containsKey) != true && it !in observed }
    }

    private fun identity(item: JsonObject): String? = JsonScalars.str(item, "id")
        ?: JsonScalars.str(item, "call_id")
}

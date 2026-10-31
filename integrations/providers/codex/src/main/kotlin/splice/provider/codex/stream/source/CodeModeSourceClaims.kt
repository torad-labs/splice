package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.IdentityHashMap

/** Provisional raw objects share the record's lease; admission transfers their charge without refunding it. */
internal class CodeModeSourceClaims {
    private val pending = IdentityHashMap<JsonObject, Long>()

    fun additional(items: Map<JsonObject, Long>, captured: List<JsonElement>?): Long {
        val owned = identities(captured)
        return items.entries.sumOf { (item, bytes) ->
            if (item in pending || item in owned) 0L else bytes
        }
    }

    /** Called only after the record lease admitted every new object. */
    fun retain(items: Map<JsonObject, Long>, captured: List<JsonElement>?) {
        val owned = identities(captured)
        for ((item, bytes) in items) {
            if (item !in owned) pending[item] = bytes
        }
    }

    fun credit(next: List<JsonElement>?, captured: List<JsonElement>?): Long {
        val owned = identities(captured)
        val admitted = identities(next)
        return pending.entries.sumOf { (item, bytes) ->
            if (item in admitted && item !in owned) bytes else 0L
        }
    }

    /** A failed stage leaves the claims untouched; a staged item now owns the same reserved bytes. */
    fun transferred(captured: List<JsonElement>?) {
        for (item in captured.orEmpty()) {
            if (item is JsonObject) pending.remove(item)
        }
    }

    private fun identities(items: List<JsonElement>?): IdentityHashMap<JsonElement, Boolean> =
        IdentityHashMap<JsonElement, Boolean>().apply {
            for (item in items.orEmpty()) put(item, true)
        }
}

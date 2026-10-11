// NEW: Oct 11, 2026 — Settings > Your data's three stores that were counted nowhere (transcript copies, compaction
// summaries, code mode work), read together and cleared one at a time.
//
// Marlin's rule for every store that holds the user's prompts: the row shows a count, Delete now works, and the
// store is stopped by a switch or its feature is named. One pair of routes serves the three, so a row reads and
// deletes them the same way.
package splice.app.control.mount

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply

private const val ERROR = "error"

/** The kept stores by the name the page asks for them with. */
internal class StoreKeptRoutes(private val stores: Map<String, KeptStore>) {
    /** Every store's count, or the reason one could not be read: one unreadable store never hides the others. */
    fun kept(): JsonReply {
        val counted = buildJsonObject { stores.forEach { (name, store) -> put(name, count(store)) } }
        return JsonReply(HttpStatusCode.OK, buildJsonObject { put("stores", counted) }.toString())
    }

    private fun count(store: KeptStore): JsonObject = buildJsonObject {
        Cancellables.runCatchingCancellable { store.held() }.fold(
            {
                put("files", it.files)
                put("bytes", it.bytes)
                it.oldestMs?.let { at -> put("oldest_epoch_ms", at) }
            },
            { put(ERROR, SafeFailureText.render(it)) },
        )
    }

    /** Clears one store, and answers with what it held, so the page can say what went. */
    fun delete(name: String): JsonReply {
        val store = stores[name] ?: return failed(HttpStatusCode.NotFound, "no kept store named $name")
        val held = Cancellables.runCatchingCancellable { store.held() }
        val failure = held.exceptionOrNull()?.let { SafeFailureText.render(it) } ?: store.clear()
        if (failure != null) return failed(HttpStatusCode.InternalServerError, failure)
        val gone = held.getOrThrow()
        val body = buildJsonObject {
            put("files", gone.files)
            put("bytes", gone.bytes)
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    private fun failed(status: HttpStatusCode, text: String) =
        JsonReply(status, buildJsonObject { put(ERROR, text) }.toString())
}

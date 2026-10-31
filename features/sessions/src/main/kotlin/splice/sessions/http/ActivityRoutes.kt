// NEW: V4-130 — the durable activity stores Settings > Your data lists and deletes: the message edges and the
// activity labels. Unwired stores are not empty stores: a control plane built without them (tests, tools) answers
// with a named 503.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.storage.DayInventory
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.KeptState

internal const val EDGES_UNWIRED = "the activity stores are not wired into this control plane"

/** The only two durable activity stores this control plane may list or delete. */
public enum class KeptActivity { EDGES, LABELS }

/** The daemon's activity stores, read per request because ControlPlane assigns them after the
 *  control server is constructed. Null = unwired. */
public fun interface ActivitySource {
    public operator fun invoke(): ActivityStores?
}

public class ActivityRoutes(private val source: ActivitySource) {
    /** The physical store census, including rows from a rolled day. No transcript content is read. */
    public fun kept(kind: KeptActivity): JsonReply {
        val stores = source() ?: return unwired()
        return Cancellables.runCatchingCancellable { keptJson(kind, stores, deleted = false) }
            .fold(
                onSuccess = { JsonReply(HttpStatusCode.OK, it) },
                onFailure = { failure -> storageFailure(failure) },
            )
    }

    /** The file lane orders this delete after already-queued appends and before later ones. */
    public fun deleteKept(kind: KeptActivity): JsonReply {
        val stores = source() ?: return unwired()
        return Cancellables.runCatchingCancellable { keptJson(kind, stores, deleted = true) }
            .fold(
                onSuccess = { JsonReply(HttpStatusCode.OK, it) },
                onFailure = { failure -> storageFailure(failure) },
            )
    }

    private fun keptJson(kind: KeptActivity, stores: ActivityStores, deleted: Boolean): String {
        val inventory = when (kind) {
            KeptActivity.EDGES -> if (deleted) stores.edges.deleteKept() else stores.edges.inventory()
            KeptActivity.LABELS -> if (deleted) stores.activity.deleteKept() else stores.activity.inventory()
        }
        val state = when (kind) {
            KeptActivity.EDGES -> stores.edgeState()
            KeptActivity.LABELS -> stores.labelState()
        }
        return inventoryJson(kind, inventory, state)
    }

    private fun inventoryJson(kind: KeptActivity, inventory: DayInventory, state: KeptState): String = buildJsonObject {
        val store = kind.name.lowercase()
        state.reason(store)?.let { put("reason", it) }
        put("rows", inventory.rows)
        // What the store costs on disk. The inventory has always measured it (DayInventory.bytes); only the payload
        // left it out, so Settings > Your data had a count with no size beside it.
        put("bytes", inventory.bytes)
        put("oldest", inventory.oldest?.toString())
    }.toString()

    private fun storageFailure(failure: Throwable): JsonReply = JsonReply(
        HttpStatusCode.InternalServerError,
        buildJsonObject {
            put("error", "cannot read or delete activity days: ${SafeFailureText.render(failure)}")
        }.toString(),
    )

    private fun unwired(): JsonReply =
        JsonReply(HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", EDGES_UNWIRED) }.toString())
}

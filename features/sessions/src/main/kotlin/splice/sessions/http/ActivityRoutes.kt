// NEW: V4-130, FEATURES.md 6 — the message edges between sessions, for the console's sessions page:
// GET /api/sessions/{id}/edges (one session's edges), GET /api/sessions/edges (every registry session's,
// keyed by session id, empty arrays included), and the `edges` summary on every /api/sessions row.
//
// THE EDGE STORE HOLDS ONLY WHAT THE SENDER DID (MessageEdgeStore): from, the `to` its SendMessage
// named, when, and for a name the session that held it then. Direction is derived HERE, per asked
// session: `out` when the session sent it, `in` when it reached the session. A `to` can be the
// session's address (`uds:<socket>`) or its name. A name reached the session stored with it and no
// other, whoever holds the name now (V4-252, MessageEdgeStore), and the edge reports that session's
// address while the registry knows it; a name stored with no session is reported verbatim and is
// nobody's `in`.
//
// THE ONE SESSION'S EDGES CARRY WHAT WAS HANDED OFF (V4-314): each edge's text, read by its tool_use id
// from the sender's transcript through the same redacted read team chat uses (SentTextSource), with the
// file it came from or why there is none ([HandedText]). The board route carries no text: it would read
// every session's transcripts on each poll.
//
// UNWIRED STORES ARE NOT EMPTY STORES. A control plane built without the stores (tests, tools) answers
// the two edges routes with a named 503, and leaves the `edges` key off the session rows rather than
// reporting zero sends for sessions nobody watched.
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
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

internal const val EDGES_UNWIRED = "the activity stores are not wired into this control plane"

// why: what a row's missing `edges` summary means when the store IS wired and on — the read refused,
// and the refusal's own words follow this. The store's state is reported separately and stays true.
internal const val EDGES_UNREAD = "the message edges could not be read: "

/** What one read of the edge store came back with. [index] is null when there is nothing to resolve
 *  against the records, and then [reason] says why in the words the console shows. Both null means a
 *  store that answered: an index resolved, nothing to explain. */
internal data class EdgeRead(val index: EdgeIndex?, val reason: String?)

/** The only two durable activity stores this control plane may list or delete. */
public enum class KeptActivity { EDGES, LABELS }

/** The daemon's activity stores, read per request because ControlPlane assigns them after the
 *  control server is constructed. Null = unwired. */
public fun interface ActivitySource {
    public operator fun invoke(): ActivityStores?
}

public class ActivityRoutes(
    private val registry: SessionSource,
    private val source: ActivitySource,
    private val texts: SentTextSource,
) {
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
        put("store", store)
        put("state", state.wire)
        state.reason(store)?.let { put("reason", it) }
        put("days", inventory.days)
        put("rows", inventory.rows)
        // What the store costs on disk. The inventory has always measured it (DayInventory.bytes); only the payload
        // left it out, so Settings > Your data had a count with no size beside it.
        put("bytes", inventory.bytes)
        put("oldest", inventory.oldest?.toString())
        put("ages_out", inventory.agesOut?.toString())
    }.toString()

    private fun storageFailure(failure: Throwable): JsonReply = JsonReply(
        HttpStatusCode.InternalServerError,
        buildJsonObject {
            put("error", "cannot read or delete activity days: ${SafeFailureText.render(failure)}")
        }.toString(),
    )

    /** GET /api/sessions/{id}/edges: `{session_id, edges}` in the SessionEdgesPayload shape, each edge
     *  with the text its sender handed off (V4-314), read once per sender from that sender's transcript. */
    public fun edges(sessionId: String): JsonReply {
        val records = registry.read()
        val index = index(records) ?: return unwired()
        val record = records.firstOrNull { it.sessionId == sessionId }
        val body = buildJsonObject {
            put("session_id", sessionId)
            val status = state()
            put("state", status?.wire)
            status?.reason("edges")?.let { put("reason", it) }
            put("edges", index.edgesOf(sessionId, record?.address, texts))
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    /** GET /api/sessions/edges: `{sessions: {<session id>: SessionEdge[]}}` for every registry session. */
    public fun boardEdges(): JsonReply {
        val index = index() ?: return unwired()
        val body = buildJsonObject {
            val status = state()
            put("state", status?.wire)
            status?.reason("edges")?.let { put("reason", it) }
            put(
                "sessions",
                buildJsonObject {
                    registry.read().forEach { record ->
                        record.sessionId?.let { put(it, index.edgesOf(it, record.address)) }
                    }
                },
            )
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    /** The store's explicit state accompanies empty reads; null means unwired, not off. */
    internal fun state(): KeptState? = source()?.edgeState()

    /** One read of the edge store, resolved against [records]; null when the stores are unwired. */
    internal fun index(records: List<SessionRecord> = registry.read()): EdgeIndex? =
        source()?.let { EdgeIndex(it.edges.edges(), records) }

    /**
     * One read of the edge store that CANNOT FAIL THE CALLER. [EdgeRead.index] is null when the stores
     * are unwired or when this read did not come back, and [EdgeRead.reason] says which.
     *
     * A HINT THAT CANNOT BE READ IS A MISSING HINT, NOT A MISSING PAGE. Reading the edges opens the
     * day files and parses their small metadata under a heap allowance, and any of that can refuse:
     * the allowance is spent (MessageEdgeCache.ensure, 16 MiB, which a long-running desk reaches and
     * never comes back from, because the day files only grow), a day file went away mid-walk, a
     * directory the daemon cannot enter. Letting it escape took `GET /api/sessions` to a 500 on the
     * everyday daemon for nearly three hours on Oct 10, so every console page that names a session
     * fell back to its id and Requests read "Session 8cb8a71d" on every row. The edges are a summary
     * ON a row. The listing IS the page. This is the same lesson as the resumable hint (cac62c805),
     * found the second time because the first fix was made where it was found rather than as a rule.
     */
    internal fun read(records: List<SessionRecord> = registry.read()): EdgeRead {
        val stores = source() ?: return EdgeRead(null, EDGES_UNWIRED)
        return Cancellables.runCatchingCancellable { EdgeIndex(stores.edges.edges(), records) }.fold(
            onSuccess = { EdgeRead(it, null) },
            onFailure = { failure -> EdgeRead(null, EDGES_UNREAD + SafeFailureText.render(failure)) },
        )
    }

    private fun unwired(): JsonReply =
        JsonReply(HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", EDGES_UNWIRED) }.toString())
}

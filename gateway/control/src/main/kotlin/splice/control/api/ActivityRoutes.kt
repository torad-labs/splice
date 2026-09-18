// NEW: V4-130, FEATURES.md 6 — the message edges between sessions, for the console's sessions page:
// GET /api/sessions/{id}/edges (one session's edges), GET /api/sessions/edges (every registry session's,
// keyed by session id, empty arrays included), and the `edges` summary on every /api/sessions row.
//
// THE EDGE STORE HOLDS ONLY WHAT THE SENDER DID (MessageEdgeStore): from, the `to` its SendMessage
// named, when. Direction is derived HERE, per asked session: `out` when the session sent it, `in` when
// its `to` names the session. A `to` can be the session's address (`uds:<socket>`) or its name, so a
// name is resolved to the address the registry holds for it before matching, and the edge reports
// that address; a name the registry does not know is reported verbatim and matches nobody's `in`.
//
// UNWIRED STORES ARE NOT EMPTY STORES. A control plane built without the stores (tests, tools) answers
// the two edges routes with a named 503, and leaves the `edges` key off the session rows rather than
// reporting zero sends for sessions nobody watched.
package splice.control.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.activity.ActivityStores
import splice.core.activity.MessageEdge
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry

internal const val EDGES_UNWIRED = "the activity stores are not wired into this control plane"

/** The daemon's activity stores, read per request because ControlPlane assigns them after the
 *  control server is constructed. Null = unwired. */
public fun interface ActivitySource {
    public operator fun invoke(): ActivityStores?
}

public class ActivityRoutes(private val registry: SessionRegistry, private val source: ActivitySource) {
    /** GET /api/sessions/{id}/edges: `{session_id, edges}` in the SessionEdgesPayload shape. */
    public fun edges(sessionId: String): JsonReply {
        val index = index() ?: return unwired()
        val record = registry.read().firstOrNull { it.sessionId == sessionId }
        val body = buildJsonObject {
            put("session_id", sessionId)
            put("edges", index.edgesOf(sessionId, record?.address))
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    /** GET /api/sessions/edges: `{sessions: {<session id>: SessionEdge[]}}` for every registry session. */
    public fun boardEdges(): JsonReply {
        val index = index() ?: return unwired()
        val body = buildJsonObject {
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

    /** One read of the edge store, resolved against [records]; null when the stores are unwired. */
    internal fun index(records: List<SessionRecord> = registry.read()): EdgeIndex? =
        source()?.let { EdgeIndex(it.edges.edges(), records) }

    private fun unwired(): JsonReply =
        JsonReply(HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", EDGES_UNWIRED) }.toString())
}

/** The edge store read once, with every `to` resolved to an address where the registry knows the name. */
internal class EdgeIndex(edges: List<MessageEdge>, records: List<SessionRecord>) {
    private val addressOfName: Map<String, String> = records
        .mapNotNull { record -> record.name?.let { name -> record.address?.let { name to it } } }
        .distinctBy { it.first }
        .toMap()
    private val resolved = edges.map { it.copy(to = addressOfName[it.to] ?: it.to) }

    /** The edges [sessionId] sent or received, oldest first, each with its direction. */
    fun edgesOf(sessionId: String, address: String?): JsonArray = buildJsonArray {
        mine(sessionId, address).forEach { (edge, direction) ->
            add(
                buildJsonObject {
                    put("from", edge.from)
                    put("to", edge.to)
                    put("at", edge.at)
                    put("direction", direction)
                },
            )
        }
    }

    /** The `edges` summary on a session row: `{sent, received, last_at | null}`. */
    fun summary(sessionId: String, address: String?): JsonObject {
        val mine = mine(sessionId, address)
        return buildJsonObject {
            put("sent", mine.count { it.second == OUT })
            put("received", mine.count { it.second == IN })
            put("last_at", mine.maxOfOrNull { it.first.at }?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    /** A session's own send is `out` even when it addressed itself: the send is the observed fact. */
    private fun mine(sessionId: String, address: String?): List<Pair<MessageEdge, String>> =
        resolved.mapNotNull { edge ->
            when {
                edge.from == sessionId -> edge to OUT
                address != null && edge.to == address -> edge to IN
                else -> null
            }
        }
}

private const val OUT = "out"
private const val IN = "in"

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.http.JsonReply
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SentTexts

internal const val EDGES_UNWIRED = "the activity stores are not wired into this control plane"

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
    /** GET /api/sessions/{id}/edges: `{session_id, edges}` in the SessionEdgesPayload shape, each edge
     *  with the text its sender handed off (V4-314), read once per sender from that sender's transcript. */
    public fun edges(sessionId: String): JsonReply {
        val records = registry.read()
        val index = index(records) ?: return unwired()
        val record = records.firstOrNull { it.sessionId == sessionId }
        val body = buildJsonObject {
            put("session_id", sessionId)
            put("edges", index.edgesOf(sessionId, record?.address, texts))
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

/** Each registry session's address, by session id: where a reader reports a stored name's session. */
internal class Addresses(records: List<SessionRecord>) {
    private val ofSession: Map<String, String> = records
        .mapNotNull { record -> record.sessionId?.let { id -> record.address?.let { id to it } } }
        .toMap()

    /** [edge] as a reader reports it: a name stored with the session that held it reports that session's
     *  address where the registry knows it. A session's address is its own; a name is not (V4-252). */
    fun reported(edge: MessageEdge): MessageEdge =
        edge.toSession?.let(ofSession::get)?.let { edge.copy(to = it) } ?: edge
}

/** The edge store read once, each edge as [Addresses.reported] against the registry. */
internal class EdgeIndex(edges: List<MessageEdge>, records: List<SessionRecord>) {
    private val reported = Addresses(records).let { addresses -> edges.map(addresses::reported) }

    /** Each registry session's head, by session id: the tree its transcript is read from first. */
    private val headOf: Map<String, String?> = records
        .mapNotNull { record -> record.sessionId?.let { id -> id to record.head } }
        .toMap()

    /** The edges [sessionId] sent or received, oldest first, each with its direction. With [texts], each
     *  also carries the text its sender handed off ([HandedText]), one read per sender for its own calls;
     *  the board passes none, since it would read every session's transcript on each poll. */
    fun edgesOf(sessionId: String, address: String?, texts: SentTextSource? = null): JsonArray {
        val mine = mine(sessionId, address)
        val found = texts?.let { source ->
            mine.groupBy { it.first.from }
                .mapValues { (sender, sent) -> source.read(sender, headOf[sender], sent.map { it.first.id }.toSet()) }
        }
        return buildJsonArray {
            mine.forEach { (edge, direction) ->
                add(
                    buildJsonObject {
                        put("from", edge.from)
                        put("to", edge.to)
                        put("at", edge.at)
                        put("direction", direction)
                        if (found != null) HandedText.put(this, edge.id, found[edge.from])
                    },
                )
            }
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

    /** A session's own send is `out` even when it addressed itself: the send is the observed fact. A
     *  name's edge is `in` for the session stored with it alone, even where that session's address has
     *  since passed to another (a reused pid's socket). */
    private fun mine(sessionId: String, address: String?): List<Pair<MessageEdge, String>> =
        reported.mapNotNull { edge ->
            when {
                edge.from == sessionId -> edge to OUT
                edge.toSession == sessionId -> edge to IN
                edge.toSession == null && address != null && edge.to == address -> edge to IN
                else -> null
            }
        }
}

private const val OUT = "out"
private const val IN = "in"

/** V4-314: the text a hand-off carried, as every edge reader reports it (a team's chat, a session's
 *  edges): the redacted text the sender's transcript holds for the call, the file it was read from, or
 *  why there is none. A text nobody found is null with its reason, never an empty string. */
internal object HandedText {
    fun put(into: JsonObjectBuilder, id: String, sent: SentTexts?) {
        val text = sent?.texts?.get(id)
        into.put("text", text)
        into.put("text_source", sent?.path?.takeIf { text != null })
        into.put("missing_reason", if (text == null) missingReason(sent) else null)
    }

    private fun missingReason(sent: SentTexts?): String = when {
        sent == null -> "no transcript lookup ran"
        sent.path == null -> "no transcript for the sender in " + sent.searched.joinToString()
        else -> "the call is not in ${sent.path}"
    }
}

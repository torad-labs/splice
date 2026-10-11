// NEW: V4-444 — edge resolution and transcript hand-off projection, separated from HTTP routes.
package splice.sessions.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.sessions.activity.EdgeTotals
import splice.sessions.activity.EdgeWanted
import splice.sessions.activity.MessageEdge
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.SentTexts

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

/** The `edges` summary on a session row, `{sent, received, last_at | null}`, from the store's counts. One snapshot
 *  serves every row of a listing, so two rows cannot disagree about the same conversation. */
internal class EdgeSummaries(private val totals: EdgeTotals) {
    fun summary(sessionId: String, address: String?): JsonObject {
        val counts = totals.of(sessionId, address)
        return buildJsonObject {
            put("sent", counts.sent)
            put("received", counts.received)
            put("last_at", counts.lastAt?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}

/** The edges that touch [sessions], by their ids and by the addresses a legacy edge names: the filter a scan of the day files
 *  keeps. It is the rule [EdgeIndex.edgesOf] files an edge under, so what a scan keeps is what the index then shows. */
internal class EdgeInterest(private val sessions: Set<String>, private val addresses: Set<String>) : EdgeWanted {
    override fun wants(edge: MessageEdge): Boolean = edge.from in sessions ||
        edge.toSession in sessions ||
        (edge.toSession == null && edge.to in addresses)
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
        sent == null -> "The sender transcript was not checked."
        sent.path == null -> "The sender transcript is no longer on this machine."
        else -> "The message is not in the sender transcript."
    }
}

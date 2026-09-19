// NEW: V4-130, FEATURES.md 6 — the message edges observed on the wire: that a session sent a
// SendMessage, to which address, when. NO TEXT, ever: the message itself stays in the transcripts and
// is read from there on demand (FEATURES.md 4.13). Kept by default, as metadata, in `edges-<day>.jsonl`
// through ActivityDays.
//
// ONLY THE SENDER IS OBSERVED. A received edge is the same edge seen from the other end: the reader
// derives direction `in` by matching an edge's `to` against the asked session's address, so one
// observation serves both sessions and the two ends can never disagree.
//
// THE TOOL-USE ID RIDES IN THE ROW so reads de-duplicate by it. The observer's in-memory de-dupe
// (MessageEdges) cannot survive a daemon restart, and a restart is exactly when a retried request can
// carry a call the store already holds; the id makes that second row harmless rather than a second
// edge.
package splice.core.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

/** The day-file prefix edges are written under. */
public const val EDGES_PREFIX: String = "edges"

/** One observed SendMessage. [from] is the sending session id, [to] the address or name the call
 *  named, [at] epoch ms of the observation, [id] the tool_use id that makes the row unique. */
public data class MessageEdge(val from: String, val to: String, val at: Long, val id: String)

public class MessageEdgeStore(private val days: ActivityDays) {
    private val json = Json { ignoreUnknownKeys = true }

    public fun record(edge: MessageEdge) {
        days.append(
            buildJsonObject {
                put("from", edge.from)
                put("to", edge.to)
                put("at", edge.at)
                put("id", edge.id)
            }.toString(),
        )
    }

    /** Every retained edge, oldest first, one per tool_use id (the earliest observation wins). */
    public fun edges(): List<MessageEdge> {
        val seen = HashSet<String>()
        return days.lines().mapNotNull(::parse).filter { seen.add(it.id) }.toList()
    }

    private fun parse(line: String): MessageEdge? {
        val row = Cancellables
            // ast-grep-ignore: kt-no-silent-result-collapse -- a torn or foreign line in a day file is not an edge; it is left out of the view
            .runCatchingCancellable { json.parseToJsonElement(line).jsonObject }
            .getOrNull() ?: return null
        return edgeOf(row)
    }

    private fun edgeOf(row: JsonObject): MessageEdge? {
        val from = JsonScalars.str(row, "from")
        val to = JsonScalars.str(row, "to")
        val at = JsonScalars.long(row, "at")
        val id = JsonScalars.str(row, "id")
        if (from == null || to == null) return null
        if (at == null || id == null) return null
        return MessageEdge(from, to, at, id)
    }
}

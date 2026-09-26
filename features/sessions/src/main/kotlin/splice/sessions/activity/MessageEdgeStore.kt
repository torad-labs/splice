// NEW: V4-130, FEATURES.md 6 — the message edges observed on the wire: that a session sent a
// SendMessage, to which address, when. NO TEXT, ever: the message itself stays in the transcripts and
// is read from there on demand (FEATURES.md 4.13). Kept by default, as metadata, in `edges-<day>.jsonl`
// through ActivityDays.
//
// ONLY THE SENDER IS OBSERVED. A received edge is the same edge seen from the other end: the reader
// derives direction `in` by matching an edge's stored session, or its `to` address, against the asked
// session, so one observation serves both sessions and the two ends can never disagree.
//
// THE TOOL-USE ID RIDES IN THE ROW so reads de-duplicate by it. The observer's in-memory de-dupe
// (MessageEdges) cannot survive a daemon restart, and a restart is exactly when a retried request can
// carry a call the store already holds; the id makes that second row harmless rather than a second
// edge.
//
// A NAME IS RESOLVED WHEN THE EDGE IS STORED (V4-252). A SendMessage `to` is an address (`uds:<socket>`)
// or a session's name, and a name moves: a later session can take it. A reader that resolved a stored
// name through the live registry filed the call under whoever held the name at read time, so a fresh
// team's board showed a rehearsal's message to its 'gpt'. The row carries `to_session`, the one live
// session that held the name when it was stored, and readers attribute a name by it alone. A name no
// live session held then, or more than one did, stores none and is attributed to no one. So is every
// name row stored before V4-252, which carries none. An address row stores none and reads as before.
package splice.sessions.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.storage.ActivityDays
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource

/** The day-file prefix edges are written under. */
internal const val EDGES_PREFIX: String = "edges"

/** One observed SendMessage. [from] is the sending session id, [to] the address or name the call
 *  named, [at] epoch ms of the observation, [id] the tool_use id that makes the row unique, and
 *  [toSession] the session that held the name [to] when the edge was stored, null for an address. */
public data class MessageEdge(
    val from: String,
    val to: String,
    val at: Long,
    val id: String,
    val toSession: String? = null,
)

/** The session a SendMessage name reaches as its edge is stored, read from [sessions]. */
public class NameHolders(private val sessions: SessionSource) {
    /** The one live registration holding [name]; null when none does, or more than one. A GONE
     *  registration holds nothing. */
    public fun sessionOf(name: String): String? = sessions.read()
        .filter { it.name == name && it.availability != SessionAvailability.GONE }
        .mapNotNull { it.sessionId }
        .distinct()
        .singleOrNull()
}

public class MessageEdgeStore(private val days: ActivityDays) {
    private val json = Json { ignoreUnknownKeys = true }

    public fun record(edge: MessageEdge) {
        days.append(
            buildJsonObject {
                put("from", edge.from)
                put("to", edge.to)
                put("at", edge.at)
                put("id", edge.id)
                if (edge.toSession != null) put("to_session", edge.toSession)
            }.toString(),
        )
    }

    /** Every retained edge, oldest first, one per tool_use id (the earliest observation wins). */
    public fun edges(): List<MessageEdge> {
        val seen = HashSet<String>()
        return days.lines().mapNotNull(::parse).filter { seen.add(it.id) }.toList()
    }

    private fun parse(line: String): MessageEdge? {
        // ast-grep-ignore: kt-no-silent-result-collapse -- a torn or foreign line in a day file is not an edge; it is left out of the view
        val row = Cancellables
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
        return MessageEdge(from, to, at, id, JsonScalars.str(row, "to_session"))
    }
}

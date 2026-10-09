// NEW: message-edge decoding shared by the retained reader and its parse-count controls.
package splice.sessions.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

internal const val TO_SESSION_KEY: String = "to_session"

/** Decodes one stored metadata line, or leaves out a torn or foreign record. */
public fun interface MessageEdgeDecode {
    public fun parse(line: String): MessageEdge?
}

internal class MessageEdgeCodec : MessageEdgeDecode {
    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(line: String): MessageEdge? {
        // A torn or foreign line is not a message edge.
        val row = JsonScalars.objectOrNull(json, line) ?: return null
        return edgeOf(row)
    }

    private fun edgeOf(row: JsonObject): MessageEdge? {
        val from = JsonScalars.str(row, "from")
        val to = JsonScalars.str(row, "to")
        val at = JsonScalars.long(row, "at")
        val id = JsonScalars.str(row, "id")
        if (from == null || to == null) return null
        if (at == null || id == null) return null
        val session = JsonScalars.str(row, TO_SESSION_KEY)
        val recipient = when {
            session != null -> RecipientResolution.Held(session)
            TO_SESSION_KEY in row -> RecipientResolution.NoHolder
            else -> RecipientResolution.Legacy
        }
        return MessageEdge(from, to, at, id, session, recipient)
    }
}

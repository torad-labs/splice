// NEW: V4-444 — how a stored edge names its recipient, and the text a hand-off carried, for the team readers.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
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

/** V4-314: the text a hand-off carried, as every edge reader reports it (a team's chat, a session's
 *  edges): the redacted text the sender's transcript holds for the call, or why there is none. A text
 *  nobody found is null with its reason, never an empty string. */
internal object HandedText {
    fun put(into: JsonObjectBuilder, id: String, sent: SentTexts?) {
        val text = sent?.texts?.get(id)
        into.put("text", text)
        into.put("missing_reason", if (text == null) missingReason(sent) else null)
    }

    private fun missingReason(sent: SentTexts?): String = when {
        sent == null -> "The sender transcript was not checked."
        sent.path == null -> "The sender transcript is no longer on this machine."
        else -> "The message is not in the sender transcript."
    }
}

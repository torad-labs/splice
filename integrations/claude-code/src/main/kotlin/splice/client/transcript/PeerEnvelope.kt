// NEW: Oct 10, 2026 — a message another session sent, read out of the user record Claude Code files it under.
//
// Claude Code writes a received message as a USER record: the text "Another Claude session sent a message:" and a
// <cross-session-message from-name="…"> envelope, then a note of its own telling the model where it came from. A reader
// that took that for what it looks like would draw a teammate's words as the person's own. Newer clients also tag the
// record (`origin.kind` = `peer`, with the sender's `name` and the clean `body`); that tag is the authority when it is
// there, and the envelope is read for a client that writes none. A record that is neither is not a received message.
package splice.client.transcript

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** A message another session sent: who it came from, and what it said without the client's envelope or note. */
internal data class ReceivedMessage(val from: String, val body: String)

private val ENVELOPE = Regex(
    """<cross-session-message\s+([^>]*)>\r?\n?(.*?)\r?\n?</cross-session-message>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")

internal class PeerEnvelope {
    fun read(record: JsonObject, text: String?): ReceivedMessage? {
        val origin = record["origin"] as? JsonObject
        val tagged = origin?.takeIf { JsonScalars.str(it, "kind") == "peer" }
        if (tagged != null) {
            val enveloped = text?.let(::enveloped)
            val from = JsonScalars.str(tagged, "name") ?: JsonScalars.str(tagged, "from") ?: enveloped?.from
            val body = JsonScalars.str(tagged, "body") ?: enveloped?.body
            return if (from != null && body != null) ReceivedMessage(from, body) else null
        }
        return text?.let(::enveloped)
    }

    private fun enveloped(text: String): ReceivedMessage? {
        val found = ENVELOPE.find(text) ?: return null
        val attributes = ATTRIBUTE.findAll(found.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        val from = attributes["from-name"] ?: attributes["from"] ?: return null
        return ReceivedMessage(from, found.groupValues[2])
    }
}

// NEW: Oct 27, 2026 (docs/peers.md, "Read what it receives"): what a peer's inbox remembers of each message it
// journaled, so a sender that sends the same message again under the same msg_id gets the first message's receipt and
// no second record, and a different message under an id already taken is refused instead of being lost or doubled. A
// mark is the sender (the session id a live registry record vouched for, else the address the frame claimed) and the
// SHA-256 of the text; the journal keeps one per message it still holds, in both its files.
package splice.sessions.peer.journal

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.security.MessageDigest
import java.util.HexFormat

/** Who sent a journaled message and what it said, as a repeat is judged. */
internal data class MessageMark(val sender: String?, val digest: String)

/** What the journal did with a message: wrote it at [Journaled.seq], or met its id already taken. */
internal sealed class JournalAdmission {
    data class Journaled(val seq: Long) : JournalAdmission()

    /** The same sender sent the same text under this id before: the first record stands, and its receipt goes again. */
    data object Repeat : JournalAdmission()

    /** Another message already came under this id, from another sender or with other text. */
    data object Conflict : JournalAdmission()
}

internal object MessageMarks {
    fun of(entry: JournalRecords.MessageEntry): MessageMark =
        MessageMark(entry.from.sessionId ?: entry.from.address ?: entry.from.claimedName, digest(entry.text))

    /** The mark of a journaled message record, or null when the record is not one. */
    fun of(record: JsonObject): MessageMark? {
        val text = JsonScalars.str(record, "text") ?: return null
        val from = record["from"] as? JsonObject
        val sender = from?.let {
            JsonScalars.str(it, "sessionId") ?: JsonScalars.str(it, "address") ?: JsonScalars.str(it, "claimedName")
        }
        return MessageMark(sender, digest(text))
    }

    /** How [mark] meets the [held] mark of the same id: null when the id is new. */
    fun admission(held: MessageMark?, mark: MessageMark): JournalAdmission? = when (held) {
        null -> null
        mark -> JournalAdmission.Repeat
        else -> JournalAdmission.Conflict
    }

    fun digest(text: String): String = MessageDigests.of(text)
}

/** The SHA-256 of a message's text, in lowercase hex: what an inbox and an outbox keep of a text instead of the text. */
public object MessageDigests {
    public fun of(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

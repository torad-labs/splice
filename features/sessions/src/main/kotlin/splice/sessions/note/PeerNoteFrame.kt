// NEW: V4-444 — one note as the frame Claude Code's inbox socket reads. The wire is not documented; this is the shape the
// V4-444 audit (splice-builder2, Sep 29 9:36 PM CT) read out of the installed 2.1.285 sender: a line of JSON holding a user
// message whose text is wrapped in <cross-session-message>, at priority "next". Nothing here asserts a human, a mode or a
// sender address: the client marks every such message as another session's (it says so to the model), which is what a note
// from the console is.
package splice.sessions.note

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Claude Code versions whose inbox frame this adapter was checked against: each took the frame in the PeerNoteFrameTest fixture on
 *  its real inbox socket and refused the same frame with another `type`, under tools/probes/claude-code-peer-note.ts. Any other
 *  version is refused, not guessed at. */
internal object PeerNoteAbi {
    val AUDITED_VERSIONS: Set<String> = setOf("2.1.282", "2.1.283", "2.1.284", "2.1.285", "2.1.286", "2.1.287")

    internal const val TAG = "cross-session-message"

    // why: the `msgV` the client's own sender writes beside every message id in the audited version.
    internal const val FRAME_VERSION = 1

    /** The name the receiving model is told the note came from. */
    internal const val FROM_NAME = "the splice console"
}

internal object PeerNoteFrame {
    private val closingTag = Regex("<(?=\\s*/\\s*${PeerNoteAbi.TAG})", RegexOption.IGNORE_CASE)

    /** [text] inside the envelope, with any closing tag in it defused the way the client's own sender defuses one (`<`
     *  becomes `<\`), so a note cannot end its own envelope. */
    fun envelope(text: String): String {
        val safe = closingTag.replace(text) { "<\\" }
        return "<${PeerNoteAbi.TAG} from-name=\"${PeerNoteAbi.FROM_NAME}\">\n$safe\n</${PeerNoteAbi.TAG}>"
    }

    /** The frame, newline-terminated. No `from` is sent: the console has no inbox, and the client's own sender omits it
     *  the same way. */
    fun encode(text: String, messageId: String): String = buildJsonObject {
        put("msgV", PeerNoteAbi.FRAME_VERSION)
        put("msg_id", messageId)
        put("type", "user")
        put(
            "message",
            buildJsonObject {
                put("role", "user")
                put("content", JsonPrimitive(envelope(text)))
            },
        )
        put("priority", "next")
    }.toString() + "\n"
}

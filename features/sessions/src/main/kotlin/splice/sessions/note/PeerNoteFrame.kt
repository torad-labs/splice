// NEW: V4-444 — one note as the frame Claude Code's inbox socket reads. The wire is not documented; this is the shape the
// V4-444 audit (splice-builder2, Sep 29 9:36 PM CT) read out of the installed 2.1.285 sender: a line of JSON holding a user
// message whose text is wrapped in <cross-session-message>, at priority "next". Nothing here asserts a human, a mode or a
// sender address: the client marks every such message as another session's (it says so to the model), which is what a note
// from the console is. Peer messaging (docs/specs/peer-messaging.md 1.1) keeps this shape for the console and builds the
// envelope with PeerWire, so a note's text is defused the same way a peer's is.
package splice.sessions.note

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the console note is: its sender name, and the Claude Code versions the probe proved take its frame. */
internal object PeerNoteAbi {
    /** The versions tools/probes/claude-code-peer-note.ts proved take the frame in the PeerNoteFrameTest fixture on their
     *  real inbox socket. The console lists them beside its sessions; the sender itself follows the rule in
     *  PeerWire.versionRefusal, so a session on a later version that says `peerProtocol: 1` is sent to as well. */
    val AUDITED_VERSIONS: Set<String> =
        setOf(
            "2.1.282", "2.1.283", "2.1.284", "2.1.285", "2.1.286", "2.1.287", "2.1.289", "2.1.296",
            "2.1.299", "2.1.300", "2.1.301", "2.1.302", "2.1.303", "2.1.304",
        )

    // why: the `msgV` the client's own sender writes beside every message id in the audited version.
    internal const val FRAME_VERSION = 1

    /** The name the receiving model is told the note came from. */
    internal const val FROM_NAME = "the splice console"
}

internal object PeerNoteFrame {
    /** [text] inside the envelope, with any envelope tag in it defused (`<` becomes `<\`), so a note can neither end its
     *  own envelope nor open a second one. */
    fun envelope(text: String): String = PeerWire.envelope(text, null, PeerNoteAbi.FROM_NAME)

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

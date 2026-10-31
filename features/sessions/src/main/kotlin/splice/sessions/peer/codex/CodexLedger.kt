// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): what a codex peer writes in its journal beside the
// messages it receives. Each hand-over of a message to the thread is journaled as `issuing` before the request goes out
// and then as how it ended, so a helper that dies between the two leaves a record of a request whose outcome nobody
// saw. When the thread closes or unloads, or its app-server goes away, the helper journals `ended`, and the daemon's
// supervisor revokes a peer whose helper died that way rather than starting it again.
package splice.sessions.peer.codex

import splice.core.util.JsonScalars
import splice.sessions.peer.journal.JournalRecords
import splice.sessions.peer.journal.PeerJournal

/** The outcomes a codex record carries, as the journal spells them. */
internal object CodexOutcome {
    const val ISSUING: String = "issuing"
    const val STARTED: String = "started"
    const val TURN_REFUSED: String = "refused"
    const val UNCONFIRMED: String = "unconfirmed"
    const val UNSENT: String = "unsent"
    const val THREAD_ENDED: String = "ended"
    const val SERVING: String = "serving"
}

/** A journaled message as a hand-over names it: its seq and its msg_id, which Codex keeps as the input's client id. */
internal data class CodexMessage(val seq: Long, val id: String)

/** One codex peer's records in its [journal]. */
public class CodexLedger(private val journal: PeerJournal) {
    /** The thread can no longer be reached: the helper is about to stop, and the peer is to be revoked. */
    public fun ended(reason: String) {
        journal.appendCodex(JournalRecords.CodexEntry(null, null, CodexOutcome.THREAD_ENDED, null, reason))
    }

    /** The helper reached its thread: an `ended` journaled by an earlier life of the peer no longer stands. */
    public fun serving() {
        journal.appendCodex(JournalRecords.CodexEntry(null, null, CodexOutcome.SERVING, null, null))
    }

    /** When the newest codex record says the thread ended, or null when it says anything else or there is none. */
    public fun endedAt(): Long? = journal.scanner.lastCodex()
        ?.takeIf { JsonScalars.str(it, "outcome") == CodexOutcome.THREAD_ENDED }
        ?.let { JsonScalars.long(it, "at") }

    internal fun step(message: CodexMessage, outcome: String, turn: String? = null, detail: String? = null) {
        journal.appendCodex(JournalRecords.CodexEntry(message.seq, message.id, outcome, turn, detail))
    }

    /** The message whose hand-over was journaled as `issuing` with no outcome after it: its seq and id, or null. */
    internal fun interrupted(): CodexMessage? {
        val last = journal.scanner.lastCodex(handOver = true)
            ?.takeIf { JsonScalars.str(it, "outcome") == CodexOutcome.ISSUING }
        val ref = JsonScalars.long(last, "ref")?.takeIf { it > journal.cursors.codex() }
        val id = JsonScalars.str(last, "id")
        return if (ref != null && id != null) CodexMessage(ref, id) else null
    }

    internal fun cursor(): Long = journal.cursors.codex()

    internal fun advance(to: Long) {
        journal.cursors.advanceCodex(to)
    }
}

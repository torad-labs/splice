// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): a codex peer's helper hands each message its inbox
// journaled to the thread, in seq order and one at a time. The text goes in Claude Code's own envelope, so the thread
// reads who sent it and can answer with `splice peer send`; the msg_id goes as the input's client id, which the
// thread's history keeps. Nothing is handed over twice: a message whose request went out and got no answer is
// journaled `unconfirmed` and left alone, and one interrupted by the helper's own death is settled from the thread's
// history. A message Codex refused gets a `dropped` receipt, so its sender learns the thread never saw it.
package splice.sessions.peer.codex

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.journal.PeerJournal

// why: the journal is read 50 records at a time, as the webhook pusher reads it, so one read stays small.
private const val CODEX_BATCH = 50

// why: spec 1.2, a receipt's detail is at most 200 characters.
private const val CODEX_RECEIPT_DETAIL_CHARS = 200

private const val ENVELOPE_END = "</cross-session-message>"
private const val ENVELOPE_END_ESCAPED = "<\\/cross-session-message>"
private const val MESSAGE_KIND = "message"

/** Where a codex peer's refusals go: a receipt to the sender of the message Codex did not take. */
public class CodexReturns(private val outbox: PeerOutbox, private val self: PeerSender) {
    internal suspend fun dropped(record: JsonObject, reason: String): Boolean {
        val address = JsonScalars.str(record["from"] as? JsonObject, "address") ?: return false
        val id = JsonScalars.str(record, "id") ?: return false
        val detail = "the Codex thread did not take it: $reason".take(CODEX_RECEIPT_DETAIL_CHARS)
        return outbox.receipt(address, id, DeliveryStatus.DROPPED, detail, self)
    }
}

/** Hands one codex peer's journaled messages to its [thread]. */
public class CodexDelivery(
    private val journal: PeerJournal,
    private val thread: CodexThread,
    private val returns: CodexReturns,
    private val log: LogSink,
) {
    private val ledger = CodexLedger(journal)

    /** Settles an interrupted hand-over, then hands over every message past the cursor and each one that arrives, until
     *  a request cannot go out. Answers why it stopped. */
    public suspend fun run(): String {
        settle()
        ledger.serving()
        var stopped: String? = null
        while (stopped == null) {
            val page = journal.read(ledger.cursor(), CODEX_BATCH, setOf(MESSAGE_KIND))
            if (page.items.isEmpty()) journal.awaitMessage(forCodex = true)
            stopped = page.items.firstNotNullOfOrNull { record -> handOver(record) }
        }
        return stopped
    }

    /** A hand-over the last helper journaled as `issuing` and never finished is settled from the thread's history:
     *  `started` when the history holds the message, `unconfirmed` otherwise. It is never sent again. */
    private suspend fun settle() {
        val open = ledger.interrupted() ?: return
        val held = thread.holds(open.id)
        if (held == true) {
            ledger.step(open, CodexOutcome.STARTED, detail = "found in the thread's history after a restart")
        } else {
            val why = if (held == null) "its history could not be read" else "its history does not hold it"
            val detail = "the helper stopped mid-request and $why; not sent again"
            ledger.step(open, CodexOutcome.UNCONFIRMED, detail = detail)
        }
        ledger.advance(open.seq)
    }

    /** One message to the thread; why the hand-overs stop, or null to go on. */
    private suspend fun handOver(record: JsonObject): String? {
        val seq = JsonScalars.long(record, "seq") ?: return null
        val id = JsonScalars.str(record, "id")
        if (id == null) {
            ledger.advance(seq)
            return null
        }
        val message = CodexMessage(seq, id)
        ledger.step(message, CodexOutcome.ISSUING)
        return when (val start = thread.startTurn(id, envelope(record))) {
            is TurnStart.Started -> finish(message, CodexOutcome.STARTED, start.turnId, null)
            is TurnStart.Unknown -> finish(message, CodexOutcome.UNCONFIRMED, null, start.reason)
            is TurnStart.Refused -> {
                finish(message, CodexOutcome.TURN_REFUSED, null, start.reason)
                if (!returns.dropped(record, start.reason)) note("a dropped receipt for $id could not be sent")
                null
            }
            is TurnStart.NotSent -> {
                ledger.step(message, CodexOutcome.UNSENT, detail = start.reason)
                start.reason
            }
        }
    }

    private fun finish(message: CodexMessage, outcome: String, turn: String?, detail: String?): String? {
        ledger.step(message, outcome, turn, detail)
        ledger.advance(message.seq)
        return null
    }

    /** The text as a Claude Code session reads a peer message: who sent it, then the text. */
    private fun envelope(record: JsonObject): String {
        val from = record["from"] as? JsonObject
        val address = JsonScalars.str(from, "address").orEmpty()
        val name = JsonScalars.str(from, "name") ?: JsonScalars.str(from, "claimedName").orEmpty()
        val text = JsonScalars.str(record, "text").orEmpty().replace(ENVELOPE_END, ENVELOPE_END_ESCAPED)
        return "<cross-session-message from=\"${attribute(address)}\" from-name=\"${attribute(name)}\">\n" +
            "$text\n$ENVELOPE_END"
    }

    private fun attribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun note(reason: String) {
        log("[peers] ${LogSafe.str(reason)}")
    }
}

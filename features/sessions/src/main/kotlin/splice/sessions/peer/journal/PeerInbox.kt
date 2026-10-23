// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.3): what a helper does with each frame its inbox socket takes.
// A message within the limits is journaled, flushed, and answered with `delivered`. One past a limit, or one the
// journal could not keep, is answered `refused` or `dropped` and not journaled. A frame behind a bad token is
// answered `denied`. A receipt for one of this peer's sends is journaled. This handler sends every receipt; its
// caller sends none.
package splice.sessions.peer.journal

import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.util.SafeFailureText
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.InboxHandler
import splice.sessions.peer.PeerConnections
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SenderCheck
import java.util.UUID

// why: spec 3.4, an inbound text is at most 32,768 bytes; a longer one is refused and never journaled.
private const val PEER_INBOUND_TEXT_BYTES = 32_768

// why: spec 1.2, a receipt's detail is at most 200 characters.
private const val PEER_INBOUND_DETAIL_CHARS = 200

private const val PEER_AUTH_TOKEN = "token"
private const val PEER_AUTH_NONE = "none"
private const val PEER_DETAIL_RATE = "rate limit"
private const val PEER_DETAIL_SIZE = "too large"
private const val PEER_DETAIL_JOURNAL = "journal write failed"
private const val PEER_DETAIL_TOKEN = "bad peer token"

/**
 * The inbox of one peer's helper. [onMessage] returns the status it answered with: [DeliveryStatus.DELIVERED] for a
 * journaled message or a repeat of one, REFUSED or DROPPED otherwise, and null when no receipt was due because the
 * frame named no sender address or no msg_id. A receipt that could not be written to the sender's socket is logged and
 * is never retried.
 */
public class PeerInbox(
    private val journal: PeerJournal,
    private val outbox: PeerOutbox,
    private val self: PeerSender,
    private val log: LogSink,
    elapsed: ElapsedClock = ElapsedClock { MonoClock.nowMs() },
) : InboxHandler, PeerConnections {
    private val limits = InboundLimits(elapsed)

    private data class Verdict(val status: DeliveryStatus, val detail: String?)

    /** This inbox for one connection, with the `auth` label its messages are journaled under. */
    private inner class Labeled(private val auth: String) : InboxHandler {
        override suspend fun onMessage(frame: InboundFrame.Message, sender: SenderCheck): DeliveryStatus? =
            receive(frame, sender, auth)

        override suspend fun onReceipt(frame: InboundFrame.Receipt, sender: SenderCheck) {
            this@PeerInbox.onReceipt(frame, sender)
        }

        override suspend fun onDenied(frame: InboundFrame.Message) {
            this@PeerInbox.onDenied(frame)
        }
    }

    /**
     * The handler for one connection: `auth: token` in the journal when [tokenPresented], `auth: none` when the
     * connection sent no auth line. The inbox itself journals no `auth`, because the contract's frames do not carry it.
     */
    override fun forConnection(tokenPresented: Boolean): InboxHandler =
        Labeled(if (tokenPresented) PEER_AUTH_TOKEN else PEER_AUTH_NONE)

    override suspend fun onMessage(frame: InboundFrame.Message, sender: SenderCheck): DeliveryStatus? =
        receive(frame, sender, null)

    override suspend fun onReceipt(frame: InboundFrame.Receipt, sender: SenderCheck) {
        val orig = frame.origMsgId
        if (orig == null || !limits.admit(frame.claimedAddress)) return
        val detail = frame.detail?.take(PEER_INBOUND_DETAIL_CHARS)
        val entry = JournalRecords.ReceiptEntry(orig, frame.status, detail, sender)
        Cancellables.runCatchingCancellable { journal.appendReceipt(entry) }
            .onFailure { note("a receipt could not be journaled (${LogSafe.str(SafeFailureText.render(it))})") }
    }

    /** A frame behind a wrong token: a `denied` receipt to the address it names, with no journal record. */
    override suspend fun onDenied(frame: InboundFrame.Message) {
        val to = frame.claimedAddress
        val id = frame.msgId
        if (to == null || id == null) return
        if (!limits.admit(to)) return
        val sent = outbox.receipt(to, id, DeliveryStatus.DENIED, PEER_DETAIL_TOKEN, self)
        if (!sent) note("a denied receipt could not be sent")
    }

    private suspend fun receive(frame: InboundFrame.Message, sender: SenderCheck, auth: String?): DeliveryStatus? {
        val verdict = decide(frame, sender, auth)
        val to = frame.claimedAddress
        val id = frame.msgId
        if (to == null || id == null) return null
        if (!outbox.receipt(to, id, verdict.status, verdict.detail, self)) {
            note("a ${verdict.status.wire} receipt could not be sent")
        }
        return verdict.status
    }

    private fun decide(frame: InboundFrame.Message, sender: SenderCheck, auth: String?): Verdict {
        val bytes = frame.text.toByteArray(Charsets.UTF_8).size
        val id = frame.msgId ?: UUID.randomUUID().toString()
        return when {
            !limits.admit(frame.claimedAddress) -> Verdict(DeliveryStatus.REFUSED, PEER_DETAIL_RATE)
            bytes > PEER_INBOUND_TEXT_BYTES -> Verdict(DeliveryStatus.REFUSED, PEER_DETAIL_SIZE)
            else -> journaled(JournalRecords.MessageEntry(id, sender, auth, frame.text, bytes))
        }
    }

    private fun journaled(entry: JournalRecords.MessageEntry): Verdict =
        Cancellables.runCatchingCancellable { journal.appendMessage(entry) }.fold(
            onSuccess = { Verdict(DeliveryStatus.DELIVERED, null) },
            onFailure = { failure ->
                note("a message could not be journaled (${LogSafe.str(SafeFailureText.render(failure))})")
                Verdict(DeliveryStatus.DROPPED, PEER_DETAIL_JOURNAL)
            },
        )

    private fun note(reason: String) {
        log("[peers] ${LogSafe.str(self.name)}: $reason")
    }
}

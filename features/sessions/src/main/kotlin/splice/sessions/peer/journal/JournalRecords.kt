// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2.3): the records of a peer's journal, one JSON object per line,
// and the codec that writes and reads them. The codec writes the defaulted fields (v and kind) and leaves absent
// values out.
package splice.sessions.peer.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.SenderCheck

// why: the layout number each journal record carries as "v", so a reader can tell a newer layout from this one.
private const val PEER_JOURNAL_LAYOUT = 1

internal const val PEER_KIND_MESSAGE: String = "message"
internal const val PEER_KIND_RECEIPT: String = "receipt"
internal const val PEER_KIND_WEBHOOK: String = "webhook"
internal const val PEER_OUTCOME_PUSHED: String = "pushed"
internal const val PEER_OUTCOME_FAILED: String = "failed"
private const val PEER_CHECK_REGISTERED = "registered"
private const val PEER_CHECK_UNREGISTERED = "unregistered"

internal object JournalRecords {
    val codec: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Who a record is from, and how it was checked: `registered` when the address named a live registry record. */
    @Serializable
    data class Sender(
        val name: String? = null,
        val sessionId: String? = null,
        val address: String? = null,
        val claimedName: String? = null,
        val check: String,
    )

    @Serializable
    data class Message(
        val v: Int = PEER_JOURNAL_LAYOUT,
        val seq: Long,
        val at: Long,
        val kind: String = PEER_KIND_MESSAGE,
        val id: String,
        val from: Sender,
        val auth: String? = null,
        val text: String,
        val bytes: Int,
        val receipt: String,
    )

    @Serializable
    data class Receipt(
        val v: Int = PEER_JOURNAL_LAYOUT,
        val seq: Long,
        val at: Long,
        val kind: String = PEER_KIND_RECEIPT,
        val origMsgId: String,
        val status: String,
        val detail: String? = null,
        val from: Sender,
    )

    @Serializable
    data class Webhook(
        val v: Int = PEER_JOURNAL_LAYOUT,
        val seq: Long,
        val at: Long,
        val kind: String = PEER_KIND_WEBHOOK,
        val ref: Long,
        val outcome: String,
        val httpStatus: Int? = null,
        val attempts: Int,
        val detail: String? = null,
    )

    /** A message to journal: what the sender claimed, how the connection was authenticated, and the text. */
    data class MessageEntry(
        val id: String,
        val from: SenderCheck,
        val auth: String?,
        val text: String,
        val bytes: Int,
    )

    /** A receipt that came back for one of this peer's sends. */
    data class ReceiptEntry(
        val origMsgId: String,
        val status: DeliveryStatus,
        val detail: String?,
        val from: SenderCheck,
    )

    /** How one push to the webhook ended: [ref] is the seq of the message it carried. */
    data class WebhookEntry(
        val ref: Long,
        val outcome: String,
        val httpStatus: Int?,
        val attempts: Int,
        val detail: String?,
    )

    fun sender(from: SenderCheck): Sender = Sender(
        name = from.name ?: from.claimedName,
        sessionId = from.sessionId,
        address = from.address,
        claimedName = from.claimedName,
        check = if (from.registered) PEER_CHECK_REGISTERED else PEER_CHECK_UNREGISTERED,
    )

    /** The sender of a webhook test body: the peer itself, which no registry record vouches for. */
    fun self(name: String): Sender = Sender(name = name, check = PEER_CHECK_UNREGISTERED)

    fun message(seq: Long, at: Long, entry: MessageEntry): String = codec.encodeToString(
        Message.serializer(),
        Message(
            seq = seq,
            at = at,
            id = entry.id,
            from = sender(entry.from),
            auth = entry.auth,
            text = entry.text,
            bytes = entry.bytes,
            receipt = DeliveryStatus.DELIVERED.wire,
        ),
    )

    fun receipt(seq: Long, at: Long, entry: ReceiptEntry): String = codec.encodeToString(
        Receipt.serializer(),
        Receipt(
            seq = seq,
            at = at,
            origMsgId = entry.origMsgId,
            status = entry.status.wire,
            detail = entry.detail,
            from = sender(entry.from),
        ),
    )

    fun webhook(seq: Long, at: Long, entry: WebhookEntry): String = codec.encodeToString(
        Webhook.serializer(),
        Webhook(
            seq = seq,
            at = at,
            ref = entry.ref,
            outcome = entry.outcome,
            httpStatus = entry.httpStatus,
            attempts = entry.attempts,
            detail = entry.detail,
        ),
    )
}

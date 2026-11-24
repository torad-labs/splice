package splice.sessions.peer

import splice.sessions.registry.SessionRecord

// The shared types of docs/specs/peer-messaging.md section 6, transcribed from it. Packages A, B, C and D code
// against these; a change to them is a change to the spec first.

public enum class DeliveryStatus(public val wire: String) {
    HELD("held"),
    DENIED("denied"),
    EXPIRED("expired"),
    DELIVERED("delivered"),
    REFUSED("refused"),
    DROPPED("dropped"),
    UNCONFIRMED("unconfirmed"),
}

/** Who a frame Splice writes is from: the peer's helper socket and its registered name. Built only from PeerStore.
 *  [socket] is the frame's `from`, where receipts go; [replySocket] is the envelope's `from`, the one address a model
 *  reads and replies to. They differ only for a Claude Code peer, whose receipts go to its return helper and whose
 *  replies go to its session's own inbox. */
public data class PeerSender(val socket: String, val name: String, val replySocket: String = socket) {
    public val address: String get() = "uds:$socket"
    public val replyAddress: String get() = "uds:$replySocket"
}

public sealed class InboundFrame {
    /** [claimedAddress] is where a receipt goes: the frame's `from`, or else the envelope's. [replyAddress] is where a
     *  reply goes: the envelope's `from`, or else the frame's. */
    public data class Message(
        val msgId: String?,
        val claimedAddress: String?,
        val claimedName: String?,
        val text: String,
        val sessionId: String? = null,
        val replyAddress: String? = claimedAddress,
    ) : InboundFrame()

    public data class Receipt(
        val origMsgId: String?,
        val status: DeliveryStatus,
        val detail: String?,
        val claimedAddress: String?,
    ) : InboundFrame()
}

public enum class PeerSubmission(public val wire: String) {
    ACCEPTED("accepted"),
    QUEUED("queued"),
    DELIVERED("delivered"),
}

public sealed class SendOutcome {
    public data class Submitted(
        val msgId: String,
        val submission: PeerSubmission = PeerSubmission.ACCEPTED,
    ) : SendOutcome()

    public data class Refused(val httpStatus: Int, val reason: String) : SendOutcome()

    public data class Failed(val reason: String) : SendOutcome()
}

public data class SenderCheck(
    val name: String?,
    val sessionId: String?,
    val address: String?,
    val claimedName: String?,
    val registered: Boolean,
)

public data class PeerWebhook(val url: String, val enabled: Boolean, val secretFile: String, val bearerFile: String?)

/** The peer key as the daemon knows it: its id and the hex sha256 of the key. The key itself lives only in peer.key. */
public data class PeerKeyRef(val id: String, val sha256: String)

// why the key is one value: eight constructor parameters trip detekt's LongParameterList, which may be ignored only
// on @Serializable classes. Readers keep the spec's names through keySha256 and keyId.
public data class PeerConfig(
    val name: String,
    val sessionId: String,
    val createdAt: Long,
    val cwd: String,
    val key: PeerKeyRef,
    val sendTo: List<String>,
    val webhook: PeerWebhook?,
) {
    public val keySha256: String get() = key.sha256
    public val keyId: String get() = key.id
}

public data class PeerPrincipal(val name: String)

/** A's: frames on the wire. */
public interface PeerOutbox {
    /** Writes [text] to [target] as [sender], under [msgId] when the caller names one, else under a fresh id. */
    public suspend fun send(target: SessionRecord, text: String, sender: PeerSender, msgId: String? = null): SendOutcome

    public suspend fun receipt(
        toAddress: String,
        origMsgId: String,
        status: DeliveryStatus,
        detail: String?,
        sender: PeerSender,
    ): Boolean
}

/** C's: what the helper hands each authenticated frame to, and what it answers. */
public interface InboxHandler {
    public suspend fun onMessage(frame: InboundFrame.Message, sender: SenderCheck): DeliveryStatus?

    public suspend fun onReceipt(frame: InboundFrame.Receipt, sender: SenderCheck)

    public suspend fun onDenied(frame: InboundFrame.Message)
}

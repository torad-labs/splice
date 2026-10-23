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
}

/** Who a frame Splice writes is from: the peer's helper socket and its registered name. Built only from PeerStore. */
public data class PeerSender(val socket: String, val name: String) {
    public val address: String get() = "uds:$socket"
}

public sealed class InboundFrame {
    public data class Message(
        val msgId: String?,
        val claimedAddress: String?,
        val claimedName: String?,
        val text: String,
    ) : InboundFrame()

    public data class Receipt(
        val origMsgId: String?,
        val status: DeliveryStatus,
        val detail: String?,
        val claimedAddress: String?,
    ) : InboundFrame()
}

public sealed class SendOutcome {
    public data class Submitted(val msgId: String) : SendOutcome()

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
    public suspend fun send(target: SessionRecord, text: String, sender: PeerSender): SendOutcome

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

/** What an inbox socket's loop (PeerInboxServer) hands its frames to: a handler for each connection, by whether it
 *  presented the token, and each message a connection with a wrong token sent. A peer helper's journal is one; a
 *  mirror of another machine's session (LAN share, milestone 6) is the other, and sends its frames on to that session. */
public interface PeerConnections {
    public fun forConnection(tokenPresented: Boolean): InboxHandler

    public suspend fun onDenied(frame: InboundFrame.Message)
}

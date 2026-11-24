// NEW: desktop-antigravity peer inbox connection (docs/specs/desktop-antigravity.md). Handles one incoming UDS
// connection to a Desktop Antigravity session using Splice's PeerWire contract: SO_PEERCRED same-user check, optional
// auth line checked in constant time against peerToken, PeerWire decoding, forwarding into DesktopSession, and
// PeerNoteSocket receipts (delivered / dropped / refused / denied).
package splice.app.desktop.acp

import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.antigravity.peer.DesktopPeerMessage
import splice.provider.antigravity.peer.DesktopPeerOutcome
import splice.sessions.note.PEER_MAX_LINE_BYTES
import splice.sessions.note.PEER_MAX_TEXT_BYTES
import splice.sessions.note.PeerAuthLine
import splice.sessions.note.PeerWire
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.MessageDigest

// why: a peer sender writes its auth and frame lines together; 10 s prevents an idle socket holding a slot.
private const val ACP_PEER_IDLE_MS = 10_000L

// why: one socket read chunk; PEER_MAX_LINE_BYTES caps the full line independently of this buffer size.
private const val ACP_PEER_READ_BYTES = 8_192

// why: bounds how long a connection waits for DesktopSession to hand off a peer frame before returning dropped.
private const val ACP_PEER_DELIVERY_MS = 5_000L

// why: newline delimiter separating one PeerWire JSON frame per line.
private const val ACP_PEER_FRAME_END: Byte = 0x0A
private const val ACP_PEER_DENIED_DETAIL = "bad peer token"
private const val ACP_PEER_TOO_LARGE_DETAIL = "too large"
private const val ACP_PEER_CLOSED_DETAIL = "session closed"
private const val ACP_PEER_TIMEOUT_DETAIL = "session did not accept message in time"
private const val ACP_PEER_DEFAULT_SENDER = "peer"

private enum class AcpConnectionAuth { TOKEN, NONE, BAD }

/** Persists returned outbound delivery receipts (`delivered` / `dropped` / etc.) into the peer's journal. */
internal fun interface AcpReceiptSink {
    suspend fun record(frame: InboundFrame.Receipt)
}

/** Collaborators shared by every accepted peer connection on one Desktop Antigravity inbox socket. */
internal data class AcpPeerContext(
    val token: String,
    val user: String,
    val sender: PeerSender,
    val outbox: PeerOutbox,
    val messages: SendChannel<DesktopPeerMessage>,
    val io: CoroutineDispatcher,
    val log: LogSink,
)

/** Serves one accepted UDS [channel] on [context] until EOF, idle timeout, or line-cap refusal. */
internal class DesktopPeerConnection(
    private val channel: SocketChannel,
    private val context: AcpPeerContext,
    private val receipts: AcpReceiptSink? = null,
    private val deliveryTimeoutMs: Long = ACP_PEER_DELIVERY_MS,
) {
    private var auth: AcpConnectionAuth? = null
    private val pending = ByteArrayOutputStream()

    suspend fun serve() {
        if (!sameUser()) return
        val chunk = ByteBuffer.allocate(ACP_PEER_READ_BYTES)
        while (true) {
            chunk.clear()
            if (!read(chunk)) break
            chunk.flip()
            if (!split(chunk)) return
        }
        if (pending.size() > 0) line(pending.toString(Charsets.UTF_8))
    }

    private fun sameUser(): Boolean {
        val peer = Cancellables.runCatchingCancellable { channel.getOption(ExtendedSocketOptions.SO_PEERCRED) }
        peer.exceptionOrNull()?.let {
            context.log("[antigravity] peer credentials check failed (${SafeFailureText.render(it)})\n")
        }
        return peer.fold(onSuccess = { it.user().name == context.user }, onFailure = { false })
    }

    private suspend fun read(chunk: ByteBuffer): Boolean {
        val read = withTimeoutOrNull(ACP_PEER_IDLE_MS) {
            val attempt = Cancellables.runCatchingCancellable { runInterruptible(context.io) { channel.read(chunk) } }
            attempt.getOrElse { -1 }
        }
        return read != null && read >= 0
    }

    private suspend fun split(chunk: ByteBuffer): Boolean {
        while (chunk.hasRemaining()) {
            val byte = chunk.get()
            if (byte == ACP_PEER_FRAME_END) {
                line(pending.toString(Charsets.UTF_8))
                pending.reset()
            } else {
                pending.write(byte.toInt())
                if (pending.size() > PEER_MAX_LINE_BYTES) return false
            }
        }
        return true
    }

    private suspend fun line(text: String) {
        val first = auth == null
        if (first) auth = authOf(text)
        if (!first || auth == AcpConnectionAuth.NONE) frame(text)
    }

    private fun authOf(line: String): AcpConnectionAuth = when (val parsed = PeerWire.authLine(line)) {
        PeerAuthLine.Absent -> AcpConnectionAuth.NONE
        PeerAuthLine.Malformed -> AcpConnectionAuth.BAD
        is PeerAuthLine.Token -> {
            val expected = context.token.toByteArray(Charsets.UTF_8)
            val given = parsed.token.toByteArray(Charsets.UTF_8)
            if (MessageDigest.isEqual(given, expected)) AcpConnectionAuth.TOKEN else AcpConnectionAuth.BAD
        }
    }

    private suspend fun frame(line: String) {
        withContext(NonCancellable) {
            when (val decoded = PeerWire.decode(line)) {
                is InboundFrame.Message -> deliver(decoded)
                is InboundFrame.Receipt -> recordReceipt(decoded)
                null -> Unit
            }
        }
    }

    private suspend fun recordReceipt(frame: InboundFrame.Receipt) {
        if (auth == AcpConnectionAuth.BAD) return
        val sink = receipts ?: return
        val failed = Cancellables.runCatchingCancellable {
            withContext(context.io) { sink.record(frame) }
        }.exceptionOrNull()
        if (failed != null) {
            context.log("[antigravity] peer receipt could not be journaled (${SafeFailureText.render(failed)})\n")
        }
    }

    private suspend fun deliver(frame: InboundFrame.Message) {
        if (auth == AcpConnectionAuth.BAD) {
            sendReceipt(frame, DeliveryStatus.DENIED, ACP_PEER_DENIED_DETAIL)
            return
        }
        if (frame.text.toByteArray(Charsets.UTF_8).size > PEER_MAX_TEXT_BYTES) {
            sendReceipt(frame, DeliveryStatus.REFUSED, ACP_PEER_TOO_LARGE_DETAIL)
            return
        }
        val outcome = CompletableDeferred<DesktopPeerOutcome>()
        val message = DesktopPeerMessage(wrapEnvelope(frame), outcome)
        val queued = context.messages.trySend(message)
        if (queued.isFailure) {
            sendReceipt(frame, DeliveryStatus.DROPPED, ACP_PEER_CLOSED_DETAIL)
            return
        }
        val result = withTimeoutOrNull(deliveryTimeoutMs) { outcome.await() }
            ?: if (message.expireBeforeSubmit(ACP_PEER_TIMEOUT_DETAIL)) {
                DesktopPeerOutcome.Dropped(ACP_PEER_TIMEOUT_DETAIL)
            } else {
                outcome.await()
            }
        when (result) {
            DesktopPeerOutcome.Delivered -> sendReceipt(frame, DeliveryStatus.DELIVERED, null)
            is DesktopPeerOutcome.Dropped -> sendReceipt(frame, DeliveryStatus.DROPPED, result.detail)
        }
    }

    private fun wrapEnvelope(frame: InboundFrame.Message): String {
        val address = frame.claimedAddress?.takeIf(PeerWire::attributeSafe)
        val name = frame.claimedName?.takeIf(PeerWire::attributeSafe) ?: ACP_PEER_DEFAULT_SENDER
        return PeerWire.envelope(frame.text, address, name)
    }

    private suspend fun sendReceipt(frame: InboundFrame.Message, status: DeliveryStatus, detail: String?) {
        val to = frame.claimedAddress ?: return
        val id = frame.msgId ?: return
        val sent = Cancellables.runCatchingCancellable {
            context.outbox.receipt(to, id, status, detail, context.sender)
        }.fold(onSuccess = { it }, onFailure = { false })
        if (!sent) context.log("[antigravity] peer receipt ${status.wire} could not be sent\n")
    }
}

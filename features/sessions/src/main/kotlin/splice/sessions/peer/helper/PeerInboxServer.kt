// NEW: peer messaging, package B (docs/specs/peer-messaging.md 1.3 and 4.2) — the helper's connection loop. A connection
// from another uid is closed with nothing read. Lines are read up to the 65,536-byte cap, and a connection that sends
// nothing for 10 s is closed. The first line may be an auth line: a token other than this helper's own `peerToken`,
// compared in constant time, refuses every frame on that connection, and a message on it goes to the inbox only as
// denied; no auth line is accepted, as Claude Code itself accepts one on Linux. Every frame is decoded with PeerWire,
// its sender looked up with PeerTargets, and handed to the inbox, which journals it and sends the receipt (4.3). Four
// connections are served at once; a line already in hand finishes even while the helper stops.
package splice.sessions.peer.helper

import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.sessions.note.PEER_MAX_LINE_BYTES
import splice.sessions.note.PeerTargets
import splice.sessions.note.PeerWire
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.InboxHandler
import splice.sessions.peer.journal.PeerInbox
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest

// why: the spec's bounded pool (4.2); a helper is one peer's inbox, and more parallel writers only queue on its journal.
private const val CONNECTIONS_AT_ONCE = 4

// why: a sender writes its two lines at once; a connection silent for 10 s holds a slot of four for nothing (4.2).
private const val IDLE_CLOSE_MS = 10_000L

// why: one read's buffer; a frame is a few kilobytes, and the line cap, not this, bounds what a connection may hold.
private const val READ_CHUNK_BYTES = 8_192

/** The byte that ends a frame: one JSON object per line. */
private const val NEWLINE: Byte = 0x0A

/** How a connection's first line vouched for it. */
private enum class ConnectionAuth { TOKEN, NONE, BAD }

/** The inbox socket's accept loop, over [server], for the helper whose token is [token]. */
public class PeerInboxServer(
    private val server: ServerSocketChannel,
    private val token: String,
    private val inbox: PeerInbox,
    private val targets: PeerTargets,
    private val log: HelperLog,
    private val io: CoroutineDispatcher,
    private val user: String,
) {
    /** Accepts until cancelled, serving at most [CONNECTIONS_AT_ONCE] connections at a time. */
    public suspend fun serve(): Unit = coroutineScope {
        val slots = Semaphore(CONNECTIONS_AT_ONCE)
        while (true) {
            slots.acquire()
            val accepted = Cancellables.runCatchingCancellable { runInterruptible(io) { server.accept() } }
            val channel = accepted.getOrElse { failure ->
                slots.release()
                // A stop interrupts the accept; that is the stop, not an accept failure.
                ensureActive()
                log.error("accept", failure)
                throw failure
            }
            launch {
                try {
                    Connection(channel).serve()
                } finally {
                    Cancellables.discard(Cancellables.runCatchingCleanup { channel.close() }, "the connection is done")
                    slots.release()
                }
            }
        }
    }

    /** One sender's connection: who it is, how its first line vouched for it, and the line being read. */
    private inner class Connection(private val channel: SocketChannel) {
        private var auth: ConnectionAuth? = null
        private val pending = ByteArrayOutputStream()

        suspend fun serve() {
            if (!sameUser()) {
                log.count("refused another uid")
                return
            }
            val chunk = ByteBuffer.allocate(READ_CHUNK_BYTES)
            while (true) {
                chunk.clear()
                if (!read(chunk)) break
                chunk.flip()
                if (!split(chunk)) return
            }
            // A last line with no newline is still a line.
            if (pending.size() > 0) line(pending.toString(Charsets.UTF_8))
        }

        /** Whether the connected process runs as this user, read from SO_PEERCRED before any byte is read. */
        private fun sameUser(): Boolean {
            val peer = Cancellables.runCatchingCancellable { channel.getOption(ExtendedSocketOptions.SO_PEERCRED) }
            peer.exceptionOrNull()?.let { log.error("peer credentials", it) }
            return peer.fold(onSuccess = { it.user().name == user }, onFailure = { false })
        }

        /** Reads into [chunk]; false once the sender closed, failed, or was silent for [IDLE_CLOSE_MS]. */
        private suspend fun read(chunk: ByteBuffer): Boolean {
            val read = withTimeoutOrNull(IDLE_CLOSE_MS) {
                val attempt = Cancellables.runCatchingCancellable { runInterruptible(io) { channel.read(chunk) } }
                attempt.getOrElse { -1 }
            }
            if (read == null) log.count("closed idle")
            return read != null && read >= 0
        }

        /** Moves [chunk] into the pending line, handing over each completed one; false once a line passed the cap. */
        private suspend fun split(chunk: ByteBuffer): Boolean {
            while (chunk.hasRemaining()) {
                val byte = chunk.get()
                if (byte == NEWLINE) {
                    line(pending.toString(Charsets.UTF_8))
                    pending.reset()
                } else {
                    pending.write(byte.toInt())
                    if (pending.size() > PEER_MAX_LINE_BYTES) {
                        log.count("line over the cap")
                        return false
                    }
                }
            }
            return true
        }

        private suspend fun line(text: String) {
            val first = auth == null
            if (first) auth = authOf(text)
            // The auth line itself is no frame; a first line that is not one is the first frame of an unauthed sender.
            if (!first || auth == ConnectionAuth.NONE) frame(text, handler)
        }

        /** The inbox for this connection, which journals how it vouched; null once a wrong token refused it. */
        private val handler: InboxHandler? by lazy {
            when (auth) {
                ConnectionAuth.TOKEN -> inbox.forConnection(tokenPresented = true)
                ConnectionAuth.BAD -> null
                else -> inbox.forConnection(tokenPresented = false)
            }
        }
    }

    private fun authOf(line: String): ConnectionAuth {
        val claimed = PeerWire.authToken(line)
        val auth = when {
            claimed == null -> ConnectionAuth.NONE
            MessageDigest.isEqual(claimed.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8)) ->
                ConnectionAuth.TOKEN
            else -> ConnectionAuth.BAD
        }
        log.count("auth: ${auth.name.lowercase()}")
        return auth
    }

    /** One frame, finished even while the helper stops: a journal append and its receipt are never cut in half. */
    private suspend fun frame(line: String, handler: InboxHandler?) {
        withContext(NonCancellable) {
            when (val frame = PeerWire.decode(line)) {
                null -> log.count("ignored line")
                is InboundFrame.Message -> message(frame, handler)
                is InboundFrame.Receipt -> receipt(frame, handler)
            }
        }
    }

    private suspend fun message(frame: InboundFrame.Message, handler: InboxHandler?) {
        if (handler == null) {
            log.count("denied message")
            inbox.onDenied(frame)
            return
        }
        val answered = handler.onMessage(frame, targets.byAddress(frame.claimedAddress, frame.claimedName))
        log.count("message ${answered?.wire ?: "unanswered"}")
    }

    private suspend fun receipt(frame: InboundFrame.Receipt, handler: InboxHandler?) {
        if (handler == null) {
            log.count("denied receipt")
            return
        }
        log.count("receipt ${frame.status.wire}")
        handler.onReceipt(frame, targets.byAddress(frame.claimedAddress))
    }
}

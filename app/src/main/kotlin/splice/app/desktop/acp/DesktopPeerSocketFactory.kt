// NEW: desktop-antigravity in-process peer UDS inbox (docs/specs/desktop-antigravity.md). Binds an owner-only Unix
// domain socket via PeerSockets (0700 dir, 0600 socket), writes the 0600 `<pid>.<sha256(socket)>.key` file via
// PeerRegistryFiles before ClaudeSessionRecord writes `<pid>.json`, serves PeerWire connections, and cleans up both
// files on close.
package splice.app.desktop.acp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.provider.antigravity.peer.DesktopPeerInbox
import splice.provider.antigravity.peer.DesktopPeerInboxFactory
import splice.provider.antigravity.peer.DesktopPeerMessage
import splice.sessions.note.PeerNoteSocket
import splice.sessions.peer.PeerSender
import splice.sessions.peer.helper.HelperFiles
import splice.sessions.peer.helper.HelperIdentity
import splice.sessions.peer.helper.PeerRegistryFiles
import splice.sessions.peer.helper.PeerSockets
import splice.sessions.peer.helper.SocketBind
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

// why: 16 random bytes hex-encoded produce the 32-character peerToken expected by PeerKeyFiles and Claude Code.
private const val ACP_PEER_TOKEN_BYTES = 16

// why: bounded concurrent inbound peer connections per session inbox so bursts queue cleanly without thread growth.
private const val ACP_PEER_CONNECTIONS = 4
private const val ACP_SESSIONS_DIR = "sessions"
private const val ACP_RUNTIME_DIR_ENV = "XDG_RUNTIME_DIR"
private const val ACP_CLOSED_REASON = "session closed"

/** Optional overrides for [DesktopPeerSocketFactory]: custom runtime socket dir, receipt store, and delivery timeout. */
internal data class DesktopPeerSocketConfig(
    val runtimeOverride: Path? = null,
    val receiptStore: DesktopPeerReceiptStore? = null,
    val deliveryTimeoutMs: Long? = null,
)

/** Opens in-process UDS peer inboxes for `splice desktop acp` sessions. */
internal class DesktopPeerSocketFactory(
    private val env: EnvReader,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val user: String = System.getProperty("user.name").orEmpty(),
    private val config: DesktopPeerSocketConfig = DesktopPeerSocketConfig(),
    private val random: SecureRandom = SecureRandom(),
) : DesktopPeerInboxFactory {
    override fun open(configDir: Path, pid: Long, sessionId: String, scope: CoroutineScope): DesktopPeerInbox? {
        val sessionsDir = configDir.resolve(ACP_SESSIONS_DIR)
        val runtimeDir = config.runtimeOverride ?: env(ACP_RUNTIME_DIR_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
        val sockets = PeerSockets(runtimeDir, resolveUid(configDir), user)
        val bound = when (val attempt = sockets.bind(sockets.pathFor(pid))) {
            is SocketBind.Bound -> attempt
            is SocketBind.Refused -> {
                log("[antigravity] peer inbox not opened (${attempt.reason})\n")
                return null
            }
        }
        val token = HexFormat.of().formatHex(ByteArray(ACP_PEER_TOKEN_BYTES).also(random::nextBytes))
        val self = HelperFiles().self(clock())
        val registryFiles = PeerRegistryFiles(listOf(sessionsDir))
        if (!writeKeyOrCleanup(registryFiles, sessionsDir, self, bound, token)) return null
        val registryDirs = listOf(sessionsDir, UserHome.claudeDir(env).resolve(ACP_SESSIONS_DIR)).distinct()
        val outbox = PeerNoteSocket(io, user = user, registryDirs = registryDirs)
        val messages = Channel<DesktopPeerMessage>(Channel.UNLIMITED)
        val context = AcpPeerContext(
            token = token,
            user = user,
            sender = PeerSender(bound.socket.toString(), sessionId),
            outbox = outbox,
            messages = messages,
            io = io,
            log = log,
        )
        val receipts = receiptSink(sessionsDir, bound.socket, outbox, pid)
        val target = PeerSocketTarget(
            bound = bound,
            pid = pid,
            sessionsDir = sessionsDir,
            registryFiles = registryFiles,
            pidDomain = self.pidDomain,
            procStart = self.procStart,
            deliveryTimeoutMs = config.deliveryTimeoutMs,
        )
        return LiveDesktopPeerInbox(target, messages, context, receipts, scope)
    }

    private fun writeKeyOrCleanup(
        registryFiles: PeerRegistryFiles,
        sessionsDir: Path,
        self: HelperIdentity,
        bound: SocketBind.Bound,
        token: String,
    ): Boolean {
        val keyError = Cancellables.runCatchingCancellable {
            registryFiles.writeKey(sessionsDir, self, bound.socket, token)
        }.exceptionOrNull() ?: return true
        log("[antigravity] peer key file not written (${SafeFailureText.render(keyError)})\n")
        Cancellables.discard(Cancellables.runCatchingCleanup { bound.channel.close() }, "closing failed bind")
        Cancellables.discard(
            Cancellables.runCatchingCleanup { Files.deleteIfExists(bound.socket) },
            "removing failed bind",
        )
        return false
    }

    private fun receiptSink(sessionsDir: Path, socket: Path, outbox: PeerNoteSocket, pid: Long): AcpReceiptSink? {
        val store = config.receiptStore ?: return null
        return DesktopPeerReceiptSink(store, sessionsDir, socket, outbox, pid, log, clock)
    }

    private fun resolveUid(fallbackPath: Path): Long? =
        listOf(Path.of("/proc/self"), fallbackPath, UserHome.dir(env)).firstNotNullOfOrNull { probe ->
            Cancellables.runCatchingCancellable {
                (Files.getAttribute(probe, "unix:uid") as? Int)?.toLong()
            }.fold(onSuccess = { it }, onFailure = { null })
        }
}

private data class PeerSocketTarget(
    val bound: SocketBind.Bound,
    val pid: Long,
    val sessionsDir: Path,
    val registryFiles: PeerRegistryFiles,
    val pidDomain: String?,
    val procStart: String?,
    val deliveryTimeoutMs: Long? = null,
)

private class LiveDesktopPeerInbox(
    private val target: PeerSocketTarget,
    private val messages: Channel<DesktopPeerMessage>,
    private val context: AcpPeerContext,
    private val receipts: AcpReceiptSink?,
    scope: CoroutineScope,
) : DesktopPeerInbox {
    private val closed = AtomicBoolean(false)
    private val acceptJob: Job = scope.launch { serve(target.bound.channel) }

    override val socketPath: String get() = target.bound.socket.toString()

    override val pidDomain: String? get() = target.pidDomain

    override val procStart: String? get() = target.procStart

    override val incoming: ReceiveChannel<DesktopPeerMessage> get() = messages

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob.cancel()
        Cancellables.discard(
            Cancellables.runCatchingCleanup { target.bound.channel.close() },
            "closing peer inbox socket",
        )
        messages.close()
        while (true) {
            val queued = messages.tryReceive().getOrNull() ?: break
            queued.expire(ACP_CLOSED_REASON)
        }
        Cancellables.discard(
            Cancellables.runCatchingCleanup {
                target.registryFiles.removeKey(target.sessionsDir, target.pid, target.bound.socket)
            },
            "removing peer inbox key file",
        )
        Cancellables.discard(
            Cancellables.runCatchingCleanup { Files.deleteIfExists(target.bound.socket) },
            "removing peer inbox socket file",
        )
    }

    private suspend fun serve(server: ServerSocketChannel) {
        val slots = Semaphore(ACP_PEER_CONNECTIONS)
        coroutineScope {
            while (!closed.get()) {
                slots.acquire()
                val accepted = Cancellables.runCatchingCancellable { runInterruptible(context.io) { server.accept() } }
                val channel = accepted.getOrElse { failure ->
                    slots.release()
                    if (closed.get() || failure is ClosedChannelException) return@coroutineScope
                    ensureActive()
                    context.log("[antigravity] peer inbox accept failed (${SafeFailureText.render(failure)})\n")
                    return@coroutineScope
                }
                launch {
                    try {
                        val conn = target.deliveryTimeoutMs?.let {
                            DesktopPeerConnection(channel, context, receipts, it)
                        } ?: DesktopPeerConnection(channel, context, receipts)
                        conn.serve()
                    } finally {
                        Cancellables.discard(
                            Cancellables.runCatchingCleanup { channel.close() },
                            "closing peer connection",
                        )
                        slots.release()
                    }
                }
            }
        }
    }
}

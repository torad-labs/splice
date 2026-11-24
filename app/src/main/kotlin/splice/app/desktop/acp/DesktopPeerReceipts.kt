// NEW: desktop-antigravity native-inbox receipt sink (docs/specs/desktop-antigravity.md). Journals returned outbound
// delivery receipts into `<stateDir>/peers/<peerName>`, resolving the owner-bound peer lazily from `PeerStore` when
// startup registration returned no name (e.g. reply dropped after the daemon applied it).
package splice.app.desktop.acp

import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.PeerTargets
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerSender
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Path

/** Where a native-inbox peer records returned outbound delivery receipts (`<stateDir>/peers/<peerName>`).
 *  When [peerName] is null (e.g. startup registration reply dropped after the daemon applied it), the sink resolves
 *  the peer owned by this live process lazily from [settingsDir]. */
internal data class DesktopPeerReceiptStore(
    val stateDir: Path,
    val settingsDir: Path,
    val peerName: String? = null,
)

/** Journals returned delivery receipts for a native-inbox ACP session, resolving the peer name lazily if needed. */
internal class DesktopPeerReceiptSink(
    private val store: DesktopPeerReceiptStore,
    sessionsDir: Path,
    private val socket: Path,
    private val outbox: PeerNoteSocket,
    pid: Long,
    private val log: LogSink,
    private val clock: WallClock,
) : AcpReceiptSink {
    private val targets = PeerTargets(SessionRegistry(sessionsDir, RouteOfPid { SessionRoute.Unknown }))
    private val owner: NativeProcess? = PeerOwnerProcesses().capture(pid)

    @Volatile
    private var cached: Pair<String, PeerInbox>? = null

    override suspend fun record(frame: InboundFrame.Receipt) {
        val inbox = resolveInbox() ?: return
        inbox.onReceipt(frame, targets.byAddress(frame.claimedAddress))
    }

    private fun resolveInbox(): PeerInbox? {
        val name = store.peerName ?: cached?.first ?: ownerPeerName() ?: return null
        val current = cached
        if (current != null && current.first == name) return current.second
        val journal = PeerJournal(PeerStateFiles(store.stateDir, name).dir, log, clock)
        val fresh = PeerInbox(journal, outbox, PeerSender(socket.toString(), name), log)
        cached = name to fresh
        return fresh
    }

    private fun ownerPeerName(): String? {
        val self = owner ?: return null
        val owners = PeerStore(store.settingsDir).nativeOwners
        return owners.entries.filter { it.value == self }.singleOrNull()?.key
    }
}

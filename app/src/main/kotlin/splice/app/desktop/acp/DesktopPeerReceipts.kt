// NEW: desktop-antigravity native-inbox receipt sink (docs/specs/desktop-antigravity.md). Journals returned outbound
// delivery receipts into `<stateDir>/peers/<peerName>`, verifying live process ownership in `PeerStore`, serializing
// first-receipt journal creation under a mutex so concurrent receipts allocate unique monotonic sequence numbers, and
// refusing symbolic links or path escapes along the journal path.
package splice.app.desktop.acp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.util.LogSafe
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Where a native-inbox peer records returned outbound delivery receipts (`<stateDir>/peers/<peerName>`).
 *  [peerName] is an optional hint from a successful startup registration; the sink always verifies live ownership of
 *  this process against [settingsDir] before writing, and falls back to owner lookup when [peerName] is null. */
internal data class DesktopPeerReceiptStore(
    val stateDir: Path,
    val settingsDir: Path,
    val peerName: String? = null,
)

/** Journals returned delivery receipts for a native-inbox ACP session with verified ownership, serialized journal
 *  creation/appends, and no-symlink containment checks. */
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
    private val mutex = Mutex()
    private var cached: Pair<String, PeerInbox>? = null

    override suspend fun record(frame: InboundFrame.Receipt) {
        val sender = targets.byAddress(frame.claimedAddress)
        mutex.withLock {
            val inbox = resolveInbox() ?: return
            inbox.onReceipt(frame, sender)
        }
    }

    private fun resolveInbox(): PeerInbox? {
        val name = verifiedPeerName()
        val dir = name?.let { PeerStateFiles(store.stateDir, it).dir }
        val refusal = dir?.let(::verifyJournalContainment)
        if (refusal != null) log("[antigravity] peer receipt journal refused (${LogSafe.str(refusal)})\n")
        val safeDir = if (refusal == null) dir else null
        if (name == null || safeDir == null) {
            cached = null
            return null
        }
        val current = cached
        if (current != null && current.first == name) return current.second
        val journal = PeerJournal(safeDir, log, clock)
        val fresh = PeerInbox(journal, outbox, PeerSender(socket.toString(), name), log)
        cached = name to fresh
        return fresh
    }

    private fun verifiedPeerName(): String? {
        val self = owner ?: return null
        val peerStore = PeerStore(store.settingsDir)
        val owners = peerStore.nativeOwners
        val preferred = store.peerName?.takeIf(String::isNotBlank)
        if (preferred != null) {
            return preferred.takeIf { peerStore.wellFormed(it) && owners[it] == self }
        }
        return owners.entries.filter { it.value == self }.singleOrNull()?.key
            ?.takeIf(peerStore::wellFormed)
    }

    private fun verifyJournalContainment(peerDir: Path): String? {
        val stateDir = store.stateDir.toAbsolutePath().normalize()
        val peersRoot = stateDir.resolve(ACP_PEERS_STATE_DIR).normalize()
        val target = peerDir.toAbsolutePath().normalize()
        if (target.parent != peersRoot) return "journal path escapes peers state directory"
        val dirRefusal = listOf(stateDir, peersRoot, target).firstNotNullOfOrNull(::checkDirectoryPath)
        if (dirRefusal != null) return dirRefusal
        val files = listOf(
            target.resolve(ACP_JOURNAL_LIVE_FILE),
            target.resolve(ACP_JOURNAL_ROLLED_FILE),
            target.resolve("$ACP_JOURNAL_LIVE_FILE.lock"),
        )
        return files.firstNotNullOfOrNull(::checkRegularFilePath)
    }

    private fun checkDirectoryPath(path: Path): String? = when {
        Files.isSymbolicLink(path) -> "symbolic link in journal directory path"
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ->
            "journal directory path is not a directory"
        else -> null
    }

    private fun checkRegularFilePath(path: Path): String? = when {
        Files.isSymbolicLink(path) -> "symbolic link at journal file"
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
            "journal file path is not a regular file"
        else -> null
    }
}

private const val ACP_PEERS_STATE_DIR = "peers"
private const val ACP_JOURNAL_LIVE_FILE = "journal.jsonl"
private const val ACP_JOURNAL_ROLLED_FILE = "journal.1.jsonl"

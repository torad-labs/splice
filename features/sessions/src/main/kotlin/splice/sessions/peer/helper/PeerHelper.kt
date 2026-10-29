// NEW: peer messaging, package B (docs/specs/peer-messaging.md 2, 4.1) — a peer helper's start and exit, the files it
// keeps under <state dir>/peers/<name>/, and how anyone tells a live helper from a dead one. Start runs in the spec's
// order: read peer.json (none: the peer is not registered), bind the inbox socket, mint the peerToken, write the
// key file and the record into each registry directory, then helper.json. Exit runs the other way: the record, the key
// file, the socket, helper.json. helper.json and the liveness check are in HelperFiles.kt, helper.log in HelperLog.kt.
// One helper per peer holds helper.lock, so a second start for the same peer stops at once instead of registering the
// peer twice. helper.lock is made 0600, as the spec's on-disk layout (2) has every file but the registry record.
package splice.sessions.peer.helper

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.store.PeerStore
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.HexFormat

// why: 16 random bytes as hex is the 32-character peerToken Claude Code itself mints (spec 2.2).
private const val PEER_TOKEN_BYTES = 16

/** The files of one peer under `<state dir>/peers/<name>/`. */
public class PeerStateFiles(stateDir: Path, name: String) {
    public val dir: Path = stateDir.resolve("peers").resolve(name)
    public val helperJson: Path = dir.resolve("helper.json")
    public val helperLog: Path = dir.resolve("helper.log")
    internal val lock: Path = dir.resolve("helper.lock")
}

/** Where a helper runs: its peer's name, the settings and state folders, the registry folders it registers in, the
 *  sockets it may bind, and the build it reports in helper.json. [anchor] is the pid the peer stands under when one
 *  host process serves many peers (Oct 28, 2026): the registry, helper.json and the socket name that process, and
 *  the peer ends when it does. Null is this process. */
public data class HelperSetup(
    val name: String,
    val settingsDir: Path,
    val stateDir: Path,
    val registryDirs: List<Path>,
    val sockets: PeerSockets,
    val version: String,
    val anchor: Long? = null,
)

/** How a helper start ended. */
public sealed class HelperStart {
    public data class Started(val helper: RunningHelper) : HelperStart()

    /** No peer.json for the name: the peer is not registered, and nothing starts. */
    public data object Unregistered : HelperStart()

    /** Another helper holds this peer's lock: it is already running. */
    public data object AlreadyRunning : HelperStart()

    public data class Failed(val reason: String) : HelperStart()
}

/** What peer.json and the webhook secret looked like at the last look, to tell a change from a removal. */
public enum class PeerFileState { SAME, CHANGED, GONE }

public class PeerHelper(
    private val setup: HelperSetup,
    private val clock: WallClock,
    private val helpers: HelperFiles = HelperFiles(),
    private val random: SecureRandom = SecureRandom(),
) {
    /** Runs the start, in the spec's order, and leaves nothing behind when a step fails. */
    public fun start(): HelperStart {
        val store = PeerStore(setup.settingsDir)
        val config = store.get(setup.name) ?: return HelperStart.Unregistered
        // A native peer keeps its exact harness owner; a dead one is never downgraded to a persistent peer.
        val owner = store.nativeOwners[setup.name]
        if (owner != null && !helpers.live(owner)) return HelperStart.Failed("the peer's native owner no longer runs")
        val peer = RecordPeer(config.name, config.sessionId, config.cwd, owner)
        val files = PeerStateFiles(setup.stateDir, setup.name)
        return when (val lock = lock(files)) {
            is LockTry.Held -> bind(config, peer, files, lock.lock)
            LockTry.Busy -> HelperStart.AlreadyRunning
            is LockTry.Refused -> HelperStart.Failed(lock.reason)
        }
    }

    private fun bind(config: PeerConfig, peer: RecordPeer, files: PeerStateFiles, lock: HelperLock): HelperStart {
        val log = HelperLog(files.helperLog, clock)
        val self = setup.anchor?.let { helpers.of(it, clock()) } ?: helpers.self(clock())
        val socket = setup.sockets.pathFor(self.pid)
        return when (val bound = setup.sockets.bind(socket)) {
            is SocketBind.Bound -> {
                if (!setup.sockets.inClientFolder(socket)) log.note("socket outside the client folder: no receipts")
                val token = HexFormat.of().formatHex(ByteArray(PEER_TOKEN_BYTES).also(random::nextBytes))
                val record = PeerRegistryRecord(PeerRegistryFiles(setup.registryDirs), peer, self, socket, clock)
                val parts = HelperParts(bound.channel, socket, token, record, log, peer.owner)
                publish(RunningHelper(config, parts, files, setup, lock), self)
            }
            is SocketBind.Refused -> {
                log.note("start failed", bound.reason)
                lock.release()
                HelperStart.Failed(bound.reason)
            }
        }
    }

    /** The key file and the record, then helper.json; a failure undoes the start. */
    private fun publish(running: RunningHelper, self: HelperIdentity): HelperStart {
        val parts = running.parts
        val helper = HelperFile(self.pid, self.procStart, parts.socket.toString(), self.startedAt, setup.version)
        val failure = parts.record.publish(parts.token)
            ?: Cancellables.runCatchingCancellable { helpers.write(running.files, helper) }.exceptionOrNull()
                ?.let { "helper.json cannot be written: ${SafeFailureText.render(it)}" }
        if (failure == null) {
            parts.log.note("started")
            return HelperStart.Started(running)
        }
        parts.log.note("start failed", failure)
        running.close()
        return HelperStart.Failed(failure)
    }

    private fun lock(files: PeerStateFiles): LockTry = Cancellables.runCatchingCancellable {
        val open = SecureFile.ownerOnlyDirectory(files.dir)
        val mode = SecureFile.CREATE_0600
        if (open != null || mode == null) {
            LockTry.Refused("the peer's state folder stays open: ${open ?: "its filesystem keeps no POSIX modes"}")
        } else {
            val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val channel = FileChannel.open(files.lock, options, mode)
            val held = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (held == null) channel.close()
            held?.let { LockTry.Held(HelperLock(channel, it)) } ?: LockTry.Busy
        }
    }.getOrElse { LockTry.Refused("the peer's lock cannot be taken: ${SafeFailureText.render(it)}") }
}

/** The pieces a started helper serves with: its bound socket, its token, its record and its log. */
public data class HelperParts(
    internal val server: ServerSocketChannel,
    public val socket: Path,
    internal val token: String,
    internal val record: PeerRegistryRecord,
    public val log: HelperLog,
    public val owner: NativeProcess? = null,
)

/** A helper that started: it serves its socket, keeps its record fresh, notices its peer.json change or go, and exits
 *  cleanly. */
public class RunningHelper internal constructor(
    config: PeerConfig,
    public val parts: HelperParts,
    public val files: PeerStateFiles,
    private val setup: HelperSetup,
    private val lock: HelperLock,
) {
    private val peerDir: Path = PeerStore(setup.settingsDir).dir(setup.name)

    @Volatile
    public var config: PeerConfig = config
        private set

    @Volatile
    private var seen: String = fingerprint(config)

    /** The accept loop over this helper's socket, handing frames to [inbox]. */
    public fun server(
        inbox: PeerInbox,
        targets: PeerTargets,
        io: CoroutineDispatcher,
        user: String,
    ): PeerInboxServer = PeerInboxServer(parts.server, parts.token, inbox, targets, parts.log, io, user)

    /** Refreshes the record in every registry directory and writes the counts. */
    public fun heartbeat() {
        parts.record.heartbeat()?.let { parts.log.note("heartbeat failed", it) }
        parts.log.flush()
    }

    /** Shows the peer as `busy` in every record while [value] holds, and as `idle` otherwise. */
    public fun busy(value: Boolean) {
        parts.record.busy(value)?.let { parts.log.note("status failed", it) }
    }

    /** Whether peer.json or the webhook secret changed or went since the last look; a change re-reads [config]. A
     *  read that fails keeps the last answer, so a passing read error is never taken for a revoke. */
    public fun look(): PeerFileState {
        val reread = Cancellables.runCatchingCancellable { PeerStore(setup.settingsDir).get(setup.name) }
        reread.exceptionOrNull()?.let { parts.log.error("peer.json", it) }
        return reread.fold(onSuccess = ::changeTo, onFailure = { PeerFileState.SAME })
    }

    /** Stops accepting, then removes the record, the key file, the socket and helper.json, and releases the lock. */
    public fun close() {
        Cancellables.discard(Cancellables.runCatchingCancellable { parts.server.close() }, "the socket is removed next")
        parts.record.withdraw()?.let { parts.log.note("withdraw failed", it) }
        val removed = Cancellables.runCatchingCancellable {
            Files.deleteIfExists(parts.socket)
            Files.deleteIfExists(files.helperJson)
        }
        removed.exceptionOrNull()?.let { parts.log.error("cleanup", it) }
        parts.log.note("stopped")
        parts.log.flush()
        lock.release()
    }

    private fun changeTo(reread: PeerConfig?): PeerFileState {
        if (reread == null) return PeerFileState.GONE
        val now = fingerprint(reread)
        if (now == seen) return PeerFileState.SAME
        seen = now
        config = reread
        return PeerFileState.CHANGED
    }

    /** The peer as peer.json says it, and the webhook secret's modification time, since a new secret keeps the name. */
    private fun fingerprint(peer: PeerConfig): String {
        val secret = peer.webhook?.secretFile?.let(peerDir::resolve)?.takeIf { Files.isRegularFile(it) }
        val secretAt = Cancellables.runCatchingCancellable { secret?.let { Files.getLastModifiedTime(it).toMillis() } }
        return "$peer\n" + secretAt.fold(onSuccess = { it ?: 0L }, onFailure = { 0L })
    }
}

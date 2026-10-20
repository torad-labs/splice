// NEW: peer messaging, package B (docs/specs/peer-messaging.md 2, 4.1) — a peer helper's start and exit, the files it
// keeps under <state dir>/peers/<name>/, and how anyone tells a live helper from a dead one. Start runs in the spec's
// order: read peer.json (none: the peer is not registered, exit 78), bind the inbox socket, mint the peerToken, write the
// key file and the record into each registry directory, then helper.json. Exit runs the other way: the record, the key
// file, the socket, helper.json. A helper is alive only while its helper.json pid runs with the procStart it recorded, so
// a reused pid is never taken for it. One helper per peer holds helper.lock, so a second start for the same peer stops
// at once instead of registering the peer twice. helper.log carries counts and errors, never text, tokens or keys.
package splice.sessions.peer.helper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.JsonlSink
import splice.core.util.LogSafe
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** The exit code of a helper whose peer is not registered: EX_CONFIG from sysexits.h, as the spec names it. */
public const val HELPER_UNREGISTERED_EXIT: Int = 78

// why: 16 random bytes as hex is the 32-character peerToken Claude Code itself mints (spec 2.2).
private const val PEER_TOKEN_BYTES = 16

// why: helper.log holds counts and errors only; a megabyte is months of them, and one rolled generation is kept.
private const val HELPER_LOG_MAX_BYTES = 1L shl 20

/** The files of one peer under `<state dir>/peers/<name>/`. */
public class PeerStateFiles(stateDir: Path, name: String) {
    public val dir: Path = stateDir.resolve("peers").resolve(name)
    public val helperJson: Path = dir.resolve("helper.json")
    public val helperLog: Path = dir.resolve("helper.log")
    internal val lock: Path = dir.resolve("helper.lock")
}

/** What helper.json says: the running helper's pid and procStart, its socket, when it started, and its build. */
@Serializable
public data class HelperFile(
    val pid: Long,
    val procStart: String? = null,
    val socket: String,
    val startedAt: Long,
    val version: String,
)

/** Reads and writes helper.json, and judges whether the helper it names is the process running under that pid. */
public class HelperFiles(
    private val identity: PidIdentity = ProcPidIdentity(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** helper.json, or null when it is absent or unreadable (both mean no helper can be vouched for). */
    public fun read(files: PeerStateFiles): HelperFile? {
        if (!Files.isRegularFile(files.helperJson)) return null
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(HelperFile.serializer(), Files.readString(files.helperJson))
        }.fold(onSuccess = { it }, onFailure = { null })
    }

    public fun write(files: PeerStateFiles, helper: HelperFile) {
        SecureFile.writeAtomic0600(files.helperJson, json.encodeToString(HelperFile.serializer(), helper))
    }

    /** Whether [helper]'s pid is running and is the process that wrote it: the same kernel start time. */
    public fun alive(helper: HelperFile): Boolean {
        val recorded = helper.procStart ?: return false
        val running = ProcessHandle.of(helper.pid).map { it.isAlive }.orElse(false)
        return running && identity.procStart(helper.pid) == recorded
    }

    /** This process as a registry record names it. */
    public fun self(now: Long): HelperIdentity {
        val pid = ProcessHandle.current().pid()
        return HelperIdentity(pid, identity.procStart(pid), identity.hostDomain(), identity.startedAt(pid) ?: now)
    }
}

/** helper.log: counts of what the helper saw and the errors it met, never a message's text, a token or a key. */
public class HelperLog(private val file: Path, private val clock: WallClock) {
    private val counts = ConcurrentHashMap<String, Long>()

    @Volatile
    private var written: Map<String, Long> = emptyMap()

    public fun count(event: String) {
        counts.merge(event, 1L, Long::plus)
    }

    public fun error(what: String, failure: Throwable) {
        note(what, SafeFailureText.render(failure))
    }

    public fun note(what: String, detail: String? = null) {
        val line = buildJsonObject {
            put("at", clock())
            put("event", LogSafe.str(what))
            detail?.let { put("detail", LogSafe.str(it)) }
        }
        append(JsonWire.string(line))
    }

    /** Writes the running counts, when they moved since the last time. */
    public fun flush() {
        val now = counts.toMap()
        if (now == written) return
        written = now
        val line = buildJsonObject {
            put("at", clock())
            put("event", "counts")
            put("counts", buildJsonObject { now.toSortedMap().forEach { (event, n) -> put(event, n) } })
        }
        append(JsonWire.string(line))
    }

    private fun append(line: String) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { JsonlSink.appendLine(file, line, HELPER_LOG_MAX_BYTES) },
            "helper.log is diagnostics; a line it could not take must not stop the inbox",
        )
    }
}

/** Where a helper runs: its peer's name, the settings and state folders, the registry folders it registers in, the
 *  sockets it may bind, and the build it reports in helper.json. */
public data class HelperSetup(
    val name: String,
    val settingsDir: Path,
    val stateDir: Path,
    val registryDirs: List<Path>,
    val sockets: PeerSockets,
    val version: String,
)

/** How a helper start ended. */
public sealed class HelperStart {
    public data class Started(val helper: RunningHelper) : HelperStart()

    /** No peer.json for the name: exit [HELPER_UNREGISTERED_EXIT]. */
    public data object Unregistered : HelperStart()

    /** Another helper holds this peer's lock: it is already running. */
    public data object AlreadyRunning : HelperStart()

    public data class Failed(val reason: String) : HelperStart()
}

/** What peer.json and the webhook secret looked like at the last look, to tell a change from a removal. */
public enum class PeerFileState { SAME, CHANGED, GONE }

/** Whether this helper got its peer's lock. */
private sealed class LockTry {
    data class Held(val lock: HelperLock) : LockTry()

    data object Busy : LockTry()

    data class Refused(val reason: String) : LockTry()
}

public class PeerHelper(
    private val setup: HelperSetup,
    private val clock: WallClock,
    private val helpers: HelperFiles = HelperFiles(),
    private val random: SecureRandom = SecureRandom(),
) {
    /** Runs the start, in the spec's order, and leaves nothing behind when a step fails. */
    public fun start(): HelperStart {
        val config = PeerStore(setup.settingsDir).get(setup.name) ?: return HelperStart.Unregistered
        val files = PeerStateFiles(setup.stateDir, setup.name)
        return when (val lock = lock(files)) {
            is LockTry.Held -> bind(config, files, lock.lock)
            LockTry.Busy -> HelperStart.AlreadyRunning
            is LockTry.Refused -> HelperStart.Failed(lock.reason)
        }
    }

    private fun bind(config: PeerConfig, files: PeerStateFiles, lock: HelperLock): HelperStart {
        val log = HelperLog(files.helperLog, clock)
        val self = helpers.self(clock())
        val socket = setup.sockets.pathFor(self.pid)
        return when (val bound = setup.sockets.bind(socket)) {
            is SocketBind.Bound -> {
                if (!setup.sockets.inClientFolder(socket)) log.note("socket outside the client folder: no receipts")
                val token = HexFormat.of().formatHex(ByteArray(PEER_TOKEN_BYTES).also(random::nextBytes))
                val peer = RecordPeer(config.name, config.sessionId, config.cwd)
                val record = PeerRegistryRecord(PeerRegistryFiles(setup.registryDirs), peer, self, socket, clock)
                val parts = HelperParts(bound.channel, socket, token, record, log)
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
        if (open != null) {
            LockTry.Refused("the peer's state folder stays open: $open")
        } else {
            val channel = FileChannel.open(files.lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
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

/** The lock that makes a helper the only one for its peer, held for the helper's whole life. */
internal class HelperLock(private val channel: FileChannel, private val lock: FileLock) {
    public fun release() {
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                lock.release()
                channel.close()
            },
            "the process is ending, and its exit releases the lock regardless",
        )
    }
}

/** The pieces a started helper serves with: its bound socket, its token, its record and its log. */
public data class HelperParts(
    internal val server: ServerSocketChannel,
    public val socket: Path,
    internal val token: String,
    internal val record: PeerRegistryRecord,
    public val log: HelperLog,
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

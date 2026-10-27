// NEW: peer messaging, package B (docs/specs/peer-messaging.md 2.2 and 4.1 step 4) — the two files that make a peer
// helper a Claude Code peer: the registry record `<pid>.json`, which puts the peer in every session's ListAgents, and the
// key file `<pid>.<sha256hex(socket path)>.key`, which holds the `peerToken` a sender writes on its auth line. They are the
// fields grokbot-mailbox writes, with Splice's values. The key file goes first and the record second, so a session never
// reads a record whose token is not yet on disk; withdrawal runs the other way. The record carries no secret and is 0644,
// as Claude Code writes its own; the key file is 0600. A codex peer's record (Oct 27, 2026) also says whether its
// thread is busy, and carries the lifecycle binding Fence reads: the Codex process that hosts the thread, and the
// helper that receives for it.
package splice.sessions.peer.helper

import kotlinx.serialization.json.Json
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/** The registry record, readable by every process of this user as Claude Code's own are. */
private const val RECORD_MODE = "rw-r--r--"

/** The status a codex peer's record shows while its thread runs a turn, as Claude Code spells a working session. The
 *  record's own default is the idle one. */
private const val BUSY_STATUS = "busy"

/** The record and key file names in each registry directory, and whether a record there is a given peer's. */
public class PeerRegistryFiles(internal val directories: List<Path>) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    public fun record(dir: Path, pid: Long): Path = dir.resolve("$pid.json")

    public fun key(dir: Path, pid: Long, socket: Path): Path = dir.resolve("$pid.${sha256Hex(socket.toString())}.key")

    /** Whether [dir] holds a record for [pid] that a process other than this peer's helper wrote. A record that cannot
     *  be read is counted as another's, so it is never deleted on a guess. */
    public fun foreign(dir: Path, pid: Long, sessionId: String): Boolean {
        val file = record(dir, pid)
        if (!Files.isRegularFile(file)) return false
        return Cancellables.runCatchingCancellable { JsonScalars.objectOrNull(json, Files.readString(file)) }.fold(
            onSuccess = { obj ->
                obj == null || JsonScalars.str(obj, "kind") != PEER_KIND ||
                    JsonScalars.str(obj, "sessionId") != sessionId
            },
            onFailure = { true },
        )
    }

    /** Removes [pid]'s record, then its key file, from every directory where the record is absent or this peer's.
     *  Answers false when some directory holds another process's record for [pid]: a reused pid, left alone. */
    public fun withdraw(pid: Long, socket: Path, sessionId: String): Boolean = directories.map { dir ->
        if (foreign(dir, pid, sessionId)) {
            false
        } else {
            Files.deleteIfExists(record(dir, pid))
            Files.deleteIfExists(key(dir, pid, socket))
            true
        }
    }.all { it }

    internal fun encode(record: RegistryRecordFile): String =
        json.encodeToString(RegistryRecordFile.serializer(), record)

    internal fun encode(key: RegistryKeyFile): String = json.encodeToString(RegistryKeyFile.serializer(), key)

    private fun sha256Hex(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

/** One running helper's registration in every registry directory: published at start, refreshed by the heartbeat,
 *  withdrawn at exit. */
public class PeerRegistryRecord(
    private val files: PeerRegistryFiles,
    private val peer: RecordPeer,
    private val identity: HelperIdentity,
    private val socket: Path,
    private val clock: WallClock,
) {
    /** Whether the record says the peer is `busy`, as a codex peer's is while its thread runs a turn; else `idle`. */
    @Volatile
    private var busy: Boolean = false

    /** The lifecycle binding, for a codex peer whose thread's Codex process attached it; written in every record. */
    private val binding: RegistryBinding? = peer.owner?.let { owner ->
        val procStart = identity.procStart
        val domain = identity.pidDomain
        if (procStart == null || domain == null) {
            null
        } else {
            RegistryBinding(
                sessionId = peer.sessionId,
                nativeOwner = RegistryProcess(owner.pid, owner.procStart, owner.pidDomain),
                peer = RegistryProcess(identity.pid, procStart, domain),
            )
        }
    }

    /** Rewrites every record as `busy` or `idle` when that changed; the reason when one failed. */
    public fun busy(value: Boolean): String? {
        if (value == busy) return null
        busy = value
        return heartbeat()
    }

    /** Writes the key file holding [token], then the record, into each directory; the reason when one failed. */
    public fun publish(token: String): String? {
        val key = files.encode(RegistryKeyFile(token, identity.procStart, identity.pidDomain))
        return Cancellables.runCatchingCancellable {
            for (dir in files.directories) {
                SecureFile.writeAtomic0600(files.key(dir, identity.pid, socket), key)
                writeRecord(dir, clock())
            }
        }.exceptionOrNull()?.let { "the registry record cannot be written: ${SafeFailureText.render(it)}" }
    }

    /** Rewrites `updatedAt` and `statusUpdatedAt` in every record, so no reader takes the peer for a stale session. */
    public fun heartbeat(): String? {
        val now = clock()
        return Cancellables.runCatchingCancellable { files.directories.forEach { writeRecord(it, now) } }
            .exceptionOrNull()?.let { "the heartbeat cannot be written: ${SafeFailureText.render(it)}" }
    }

    /** Removes the record, then the key file, from every directory. */
    public fun withdraw(): String? = Cancellables.runCatchingCancellable {
        files.withdraw(identity.pid, socket, peer.sessionId)
    }.exceptionOrNull()?.let { "the registry record cannot be removed: ${SafeFailureText.render(it)}" }

    private fun writeRecord(dir: Path, now: Long) {
        val record = RegistryRecordFile(
            pid = identity.pid,
            sessionId = peer.sessionId,
            cwd = peer.cwd,
            startedAt = identity.startedAt,
            procStart = identity.procStart,
            version = "$PEER_KIND/$GATEWAY_VERSION",
            pidDomain = identity.pidDomain,
            messagingSocketPath = socket.toString(),
            name = peer.name,
            nameSince = identity.startedAt,
            updatedAt = now,
            statusUpdatedAt = now,
            lifecycleBinding = binding,
        )
        val path = files.record(dir, identity.pid)
        SecureFile.writeAtomic0600(path, files.encode(if (busy) record.copy(status = BUSY_STATUS) else record))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(RECORD_MODE))
    }
}

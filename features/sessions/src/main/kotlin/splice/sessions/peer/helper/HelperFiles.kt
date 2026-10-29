// NEW: Oct 27, 2026: helper.json and how anyone tells a live helper from a dead one (docs/specs/peer-messaging.md 2),
// moved out of PeerHelper.kt unchanged when a codex peer's owning process joined the liveness checks. A helper is alive
// only while its helper.json pid runs with the procStart it recorded, so a reused pid is never taken for it.
package splice.sessions.peer.helper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files

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
    private val owners = PeerOwnerProcesses(identity)
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

    /** Whether [process] still runs, as the same process: its pid alive, its kernel start and domain unchanged. */
    public fun live(process: NativeProcess): Boolean = owners.live(process)

    /** This process as a registry record names it. */
    public fun self(now: Long): HelperIdentity = of(ProcessHandle.current().pid(), now)

    /** The process [pid] as a registry record names it. */
    internal fun of(pid: Long, now: Long): HelperIdentity =
        HelperIdentity(pid, identity.procStart(pid), identity.hostDomain(), identity.startedAt(pid) ?: now)
}

/** The file lock remains owned until the running helper has withdrawn its files and socket. */
internal class HelperLock(private val channel: FileChannel, private val lock: FileLock) {
    fun release() {
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                lock.release()
                channel.close()
            },
            "the process is ending, and its exit releases the lock regardless",
        )
    }
}

/** Whether startup acquired this peer's one helper.lock. */
internal sealed class LockTry {
    data class Held(val lock: HelperLock) : LockTry()
    data object Busy : LockTry()
    data class Refused(val reason: String) : LockTry()
}

// NEW: macOS parity — birth-validated, owner-only process declarations replace unavailable process environments.
package splice.core.process

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// Six short, non-secret fields never need an unbounded state-file payload.
private const val RECORD_LIMIT = 4096L

/** A live process's birth, null when the operating system cannot disclose it. */
public data class LaunchProcess(val startedAt: Instant?)

/** A null answer means the PID is gone, not merely that its birth is unavailable. */
public fun interface LaunchProcessIdentity {
    public fun inspect(pid: Long): LaunchProcess?
}

/** Non-secret facts the launcher declares before exec; the PID and birth survive exec. */
@Serializable
public data class LaunchOwner(
    val pid: Long,
    val startedAt: String,
    val head: String,
    val baseUrl: String,
    val kind: String,
    val origin: String,
)

/** Owner-only launch declarations, independently validated against ProcessHandle on every read. */
public class LaunchOwners(
    stateDir: Path,
    private val processes: LaunchProcessIdentity = LaunchProcessIdentity { pid ->
        ProcessHandle.of(pid).filter { it.isAlive }
            .map { LaunchProcess(it.info().startInstant().orElse(null)) }.orElse(null)
    },
) {
    private val directory = stateDir.resolve("launch-owners")
    private val json = Json { ignoreUnknownKeys = true }

    /** Record only a live process with a known birth, never an unverified PID. */
    public fun write(pid: Long, head: String, baseUrl: String, kind: String, origin: String) {
        require(pid > 0 && head.isNotBlank() && kind in setOf("session", "login") && origin in setOf("hook", "other"))
        val startedAt = requireNotNull(processes.inspect(pid)?.startedAt) { "launch process birth is unavailable" }
        require(SecureFile.ownerOnlyDirectory(directory) == null) { "launch owner directory is not owner-only" }
        reap()
        SecureFile.writeAtomic0600(
            directory.resolve("$pid.json"),
            json.encodeToString(LaunchOwner(pid, startedAt.toString(), head, baseUrl, kind, origin)),
        )
    }

    private fun record(file: Path): LaunchOwner? = try {
        if (Files.size(file) > RECORD_LIMIT) null else json.decodeFromString<LaunchOwner>(Files.readString(file))
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Unknown, malformed and reused identities never name a head. A gone record is reaped. */
    public fun read(pid: Long): LaunchOwner? {
        if (pid <= 0) return null
        val file = directory.resolve("$pid.json")
        // An unreadable or malformed owner record is no evidence of ownership.
        val owner = record(file) ?: return null
        val process = processes.inspect(pid)
        if (process == null) Files.deleteIfExists(file)
        return owner.takeIf {
            it.pid == pid && process?.startedAt != null && it.startedAt == process.startedAt.toString()
        }
    }

    /** A new launch also removes declarations whose PID is no longer live. */
    public fun reap() {
        if (!Files.isDirectory(directory)) return
        Files.newDirectoryStream(directory, "*.json").use { entries ->
            for (file in entries) {
                val pid = file.fileName.toString().removeSuffix(".json").toLongOrNull() ?: continue
                if (pid > 0 && processes.inspect(pid) == null) Files.deleteIfExists(file)
            }
        }
    }
}

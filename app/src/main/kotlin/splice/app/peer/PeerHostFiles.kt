// NEW: Oct 28, 2026 (Marcos: "yeah replcace the java process per peer"): the files the daemon and the one peer-host
// process share under `<state dir>/peer-host/`. host.lock keeps a second host out, host.json names the host that runs
// and its build, and `start/<name>` asks the host of that build to serve a peer.
package splice.app.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** What host.json says: the running host's pid and procStart, when it started, and its build. */
@Serializable
internal data class HostFile(val pid: Long, val procStart: String? = null, val startedAt: Long, val version: String)

internal class PeerHostFiles(stateDir: Path) {
    val dir: Path = stateDir.resolve("peer-host")
    val lock: Path = dir.resolve("host.lock")
    val hostJson: Path = dir.resolve("host.json")
    private val requests: Path = dir.resolve("start")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Asks the host of [build] to serve [name]; a host of another build leaves the request for its successor. */
    fun request(name: String, build: String) {
        SecureFile.ownerOnlyDirectory(dir)?.let { error("the peer host's folder stays open: $it") }
        SecureFile.writeAtomic0600(requests.resolve(name), build)
    }

    /** The names asked of [build], each taken once: its request is deleted as it is read. */
    fun take(build: String): List<String> {
        val asked = try {
            Files.list(requests).use { files -> files.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        return asked.filter { it.fileName.toString().none { c -> c == '.' } }.mapNotNull { file ->
            val read = Cancellables.runCatchingCancellable { Files.readString(file) }
                .fold(onSuccess = { it }, onFailure = { null })
            if (read != build) return@mapNotNull null
            if (Files.deleteIfExists(file)) file.fileName.toString() else null
        }
    }

    /** Whether a request waits for [build]. */
    fun waiting(build: String): Boolean = try {
        Files.list(requests).use { files ->
            files.anyMatch { file ->
                Cancellables.runCatchingCancellable { Files.readString(file) }
                    .fold(onSuccess = { it == build }, onFailure = { false })
            }
        }
    } catch (_: NoSuchFileException) {
        false
    }

    fun host(): HostFile? {
        if (!Files.isRegularFile(hostJson)) return null
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(HostFile.serializer(), Files.readString(hostJson))
        }.fold(onSuccess = { it }, onFailure = { null })
    }

    fun write(host: HostFile) {
        SecureFile.writeAtomic0600(hostJson, json.encodeToString(HostFile.serializer(), host))
    }
}

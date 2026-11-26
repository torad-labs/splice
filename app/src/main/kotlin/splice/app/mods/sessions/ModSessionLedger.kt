package splice.app.mods.sessions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.perf.KeptHistory
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.launch.ModsOff
import splice.sessions.registry.SEEN_SESSIONS_DIR
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class ModSessionPair(val id: String, val owner: String?)

@Serializable
internal data class ModSessionEntry(
    val pair: ModSessionPair,
    val at: Long,
    val context: ModSessionContext,
    val ended: Boolean = false,
)

@Serializable
internal data class ModSessionContext(val cwd: String, val labels: JsonObject, val off: ModsOff?)

/** The durable pairing includes the scrubbed context, never a new read of raw launch text after a restart. */
internal class ModSessionLedger(
    modsDir: Path,
    stateDir: Path,
    private val log: LogSink,
    private val history: KeptHistory? = null,
) {
    private val dir = modsDir.resolve("sessions")
    private val existing = stateDir.resolve(SEEN_SESSIONS_DIR)
    private val sessionId = Regex("[A-Za-z0-9_-]{1,128}")
    private val ownerId = Regex("[0-9a-f-]{36}")

    fun valid(id: String): Boolean = sessionId.matches(id)

    fun seen(): List<String> = rows(existing).map { it.fileName.toString().removeSuffix(".json") }.filter(::valid)

    fun restore(now: Long, launches: ModLaunches): List<ModSessionEntry> {
        val cutoff = history?.now()?.cutoffMs(now) ?: Long.MIN_VALUE
        return rows(dir).mapNotNull { file ->
            val entry = read(file) ?: return@mapNotNull null
            val process = entry.pair.owner?.let(launches::stored)?.processStart
            val stillTracked = process != null && !entry.ended
            if (entry.at < cutoff && !stillTracked) {
                Files.deleteIfExists(file)
                null
            } else {
                entry
            }
        }
    }

    fun save(entry: ModSessionEntry) {
        val file = path(entry.pair)
        val _ = AsyncFileIo.submit(owner = dir) {
            Cancellables.runCatchingCancellable {
                check(SecureFile.ownerOnlyDirectory(dir) == null) { "mod session directory is not owner-only" }
                SecureFile.writeAtomic0600(file, Json.encodeToString(entry))
            }.onFailure { log("[mods] could not write session history\n") }
        }
    }

    private fun read(file: Path): ModSessionEntry? = try {
        Json.decodeFromString<ModSessionEntry>(Files.readString(file)).takeIf {
            valid(it.pair.id) && (it.pair.owner == null || ownerId.matches(it.pair.owner)) && path(it.pair) == file
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun path(pair: ModSessionPair): Path = dir.resolve("${pair.id}.${pair.owner.orEmpty()}.json")

    private fun rows(folder: Path): List<Path> = try {
        Files.newDirectoryStream(folder, "*.json").use { it.toList() }
    } catch (_: IOException) {
        emptyList()
    }
}

package splice.app.mods.sessions

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Share one scrubbed session context across the pipe, the ledger and active session rows. */
internal data class ModPendingSession(
    val pair: ModSessionPair,
    val off: ModsOff?,
    private val projected: Lazy<ModSessionEntry?>,
) {
    val entry: ModSessionEntry? get() = projected.value
}

internal data class ModSessionIndex(
    val ids: Set<String>,
    val ownerless: Set<String>,
    val active: List<ModSessionEntry>,
)

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

    suspend fun restore(now: Long, launches: ModLaunches): ModSessionIndex {
        val ids = mutableSetOf<String>()
        val ownerless = mutableSetOf<String>()
        val active = mutableListOf<ModSessionEntry>()
        val cutoff = history?.now()?.cutoffMs(now) ?: Long.MIN_VALUE
        visit(existing) { file ->
            val id = file.fileName.toString().removeSuffix(".json")
            if (valid(id)) ids.add(id)
        }
        visit(dir) { file ->
            val pair = key(file) ?: return@visit
            val launch = pair.owner?.let(launches::stored)
            val entry = launch?.let { read(file) }?.takeUnless(ModSessionEntry::ended)
            if (entry == null && expired(file, cutoff)) {
                Files.deleteIfExists(file)
            } else {
                ids.add(pair.id)
                if (pair.owner == null) ownerless.add(pair.id)
                if (entry != null) active.add(entry)
            }
        }
        return ModSessionIndex(ids, ownerless, active)
    }

    private fun key(file: Path): ModSessionPair? {
        val name = file.fileName.toString().removeSuffix(".json")
        if ('.' !in name) return null
        val id = name.substringBefore('.')
        val owner = name.substringAfter('.').ifEmpty { null }
        return ModSessionPair(id, owner).takeIf { valid(id) && (owner == null || ownerId.matches(owner)) }
    }

    private fun expired(file: Path, cutoff: Long): Boolean =
        cutoff != Long.MIN_VALUE && Files.getLastModifiedTime(file).toMillis() < cutoff

    fun save(pending: ModPendingSession) {
        val _ = AsyncFileIo.submit(owner = dir) {
            Cancellables.runCatchingCancellable {
                val entry = pending.entry ?: return@runCatchingCancellable
                check(SecureFile.ownerOnlyDirectory(dir) == null) { "mod session directory is not owner-only" }
                SecureFile.writeAtomic0600(path(entry.pair), Json.encodeToString(entry))
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

    private suspend inline fun visit(folder: Path, each: (Path) -> Unit) {
        if (!Files.isDirectory(folder)) return
        try {
            Files.newDirectoryStream(folder, "*.json").use { files ->
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    each(file)
                }
            }
        } catch (_: IOException) {
            log("[mods] could not read session history\n")
        }
    }
}

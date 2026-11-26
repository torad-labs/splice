package splice.app.mods.sessions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import splice.launch.LaunchNotice
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class StoredModLaunch(
    val launch: LaunchNotice,
    val recordedAt: Long,
    val processStart: String?,
    val goneAt: Long? = null,
) {
    fun isAlive(): Boolean = processStart != null && processStart == launch.pid?.let(ModLaunchProcess::birth)
}

internal object ModLaunchProcess {
    fun birth(pid: Long): String? = ProcessHandle.of(pid).filter { it.isAlive }
        .flatMap { it.info().startInstant() }.map { it.toString() }.orElse(null)
}

/** One owner-only record per launch so an already-running client keeps its settings after a daemon restart. */
internal class ModLaunchFiles(modsDir: Path) {
    private val dir = modsDir.resolve("launches")
    private val ownerFile = Regex("[0-9a-f-]{36}\\.json")

    fun read(): List<StoredModLaunch> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files ->
            files.filter { ownerFile.matches(it.fileName.toString()) }.map(::readOne).toList().filterNotNull()
        }
    }

    private fun readOne(path: Path): StoredModLaunch? = try {
        Json.decodeFromString<StoredModLaunch>(Files.readString(path)).takeIf {
            path.fileName.toString() == "${it.launch.owner}.json"
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun write(record: StoredModLaunch) {
        val path = path(record)
        check(SecureFile.ownerOnlyDirectory(dir) == null) { "mod launch directory is not owner-only" }
        SecureFile.writeAtomic0600(path, Json.encodeToString(record))
    }

    fun path(record: StoredModLaunch): Path = dir.resolve("${record.launch.owner}.json")
}

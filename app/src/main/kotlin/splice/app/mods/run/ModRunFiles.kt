package splice.app.mods.run

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.app.mods.install.ModPrivateDir
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

@Serializable
private data class ModRunRecord(val pid: Long, val birth: String, val grouped: Boolean = false)

internal data class ModRunOwner(val leader: ModIdentity, val grouped: Boolean)

internal class ModRunFiles(modsDir: Path, private val log: LogSink) {
    val directory: Path = modsDir.resolve("run")

    fun names(): List<String> = Cancellables.runCatchingBestEffort {
        if (!Files.isDirectory(directory)) {
            emptyList()
        } else {
            Files.newDirectoryStream(directory, "*.json").use { stream ->
                stream.map { it.fileName.toString().removeSuffix(".json") }
            }
        }
    }.getOrElse {
        log("[mods] cannot read Bun process records: ${SafeFailureText.render(it)}\n")
        emptyList()
    }

    fun read(name: String): ModRunOwner? {
        val path = directory.resolve("$name.json")
        return try {
            val record = Json.decodeFromString<ModRunRecord>(Files.readString(path))
            ModRunOwner(ModIdentity(record.pid, Instant.parse(record.birth)), record.grouped)
        } catch (_: NoSuchFileException) {
            null
        } catch (failure: IllegalArgumentException) {
            throw unreadable(path, failure)
        } catch (failure: DateTimeParseException) {
            throw unreadable(path, failure)
        }
    }

    fun write(name: String, owner: ModRunOwner) {
        ModPrivateDir.create(directory)
        val record = ModRunRecord(owner.leader.pid, owner.leader.birth.toString(), owner.grouped)
        SecureFile.writeAtomic0600(directory.resolve("$name.json"), Json.encodeToString(record))
    }

    fun clear(name: String) {
        val path = directory.resolve("$name.json")
        Cancellables.runCatchingCancellable { Files.deleteIfExists(path) }.onFailure {
            log("[mods] cannot clear ${path.fileName}: ${SafeFailureText.render(it)}\n")
        }
    }

    private fun unreadable(path: Path, cause: Exception): IOException =
        IOException("cannot read ${path.fileName}; clear it only after the old mod and its helpers have stopped", cause)
}

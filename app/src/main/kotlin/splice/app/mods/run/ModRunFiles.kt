package splice.app.mods.run

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.app.mods.install.ModPrivateDir
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

@Serializable
private data class ModRunRecord(
    val pid: Long,
    val birth: String,
    // Before the Bun guardian, the process in the record also led its own group.
    val grouped: Boolean = false,
    val hostPid: Long? = null,
    val hostBirth: String? = null,
)

internal data class ModRunOwner(val guardian: ModIdentity, val group: ModIdentity? = null)

internal class ModRunFiles(modsDir: Path) {
    val directory: Path = modsDir.resolve("run")

    fun read(name: String): ModRunOwner? {
        val path = directory.resolve("$name.json")
        return try {
            decode(Files.readString(path))
        } catch (_: NoSuchFileException) {
            null
        } catch (failure: IllegalArgumentException) {
            throw unreadable(path, failure)
        } catch (failure: DateTimeParseException) {
            throw unreadable(path, failure)
        }
    }

    private fun decode(text: String): ModRunOwner {
        val record = Json.decodeFromString<ModRunRecord>(text)
        val guardian = ModIdentity(record.pid, Instant.parse(record.birth))
        val group = when {
            record.hostPid != null -> ModIdentity(record.hostPid, Instant.parse(checkNotNull(record.hostBirth)))
            record.grouped -> guardian
            else -> null
        }
        return ModRunOwner(guardian, group)
    }

    fun write(name: String, owner: ModRunOwner) {
        ModPrivateDir.create(directory)
        val record = ModRunRecord(
            owner.guardian.pid,
            owner.guardian.birth.toString(),
            hostPid = owner.group?.pid,
            hostBirth = owner.group?.birth?.toString(),
        )
        SecureFile.writeAtomic0600(directory.resolve("$name.json"), Json.encodeToString(record))
    }

    private fun unreadable(path: Path, cause: Exception): IOException =
        IOException("cannot read ${path.fileName}; clear it only after the earlier mod group has stopped", cause)
}

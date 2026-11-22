// Encodes, decodes, and validates server-mode durable state snapshots (F14).
package splice.server.backup

import kotlinx.serialization.json.Json
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

internal class ServerSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(bytes: ByteArray): ServerSnapshot {
        val parsed = try {
            json.decodeFromString(ServerSnapshot.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            error("refusing to start empty over a corrupt backup snapshot: ${SafeFailureText.render(e)}")
        }
        val allowed = snapshotStateFiles.toSet()
        val unknown = parsed.files.keys.filterNot { it in allowed }
        if (unknown.isNotEmpty()) {
            error("refusing to restore backup snapshot containing unexpected file(s): ${unknown.joinToString(", ")}")
        }
        return parsed
    }

    fun encode(takenAt: String, files: Map<String, String>): ByteArray = json.encodeToString(
        ServerSnapshot.serializer(),
        ServerSnapshot(BACKUP_SNAPSHOT_VERSION, takenAt, files),
    ).toByteArray(Charsets.UTF_8)

    fun readLocalFiles(folder: Path): Map<String, String> = buildMap {
        for (name in snapshotStateFiles) {
            val file = folder.resolve(name)
            if (Files.isRegularFile(file)) put(name, Files.readString(file, Charsets.UTF_8))
        }
    }
}

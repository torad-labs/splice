// NEW: server mode (docs/specs/server-mode.md). One JSON file under the daemon's state directory that the server's
// own records live in (the keys, each person's limits): read once when the daemon starts, and written whole,
// owner-only and atomically, at each change. A file that is there but cannot be read is a failure the caller hears, so
// a damaged file is never taken for an empty one and then written over.
package splice.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal class StateFile<T>(val path: Path, private val serializer: KSerializer<T>) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** What the file holds, or null when there is no file yet. Throws when one is there and cannot be read. */
    fun read(): T? {
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            return null
        }
        return json.decodeFromString(serializer, text)
    }

    fun write(value: T) {
        SecureFile.writeAtomic0600(path, json.encodeToString(serializer, value))
    }
}

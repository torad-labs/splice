// NEW: server mode (docs/specs/server-mode.md). One JSON file under splice's state directory that a record of server
// mode lives in (on the server, the keys and each person's limits; on a laptop, what a sign-in to a server keeps):
// read when it is needed, and written whole, owner-only and atomically, at each change. A file that is there but
// cannot be read is a failure the caller hears, so a damaged file is never taken for an empty one and written over.
package splice.share

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

public class StateFile<T>(public val path: Path, private val serializer: KSerializer<T>) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** What the file holds, or null when there is no file yet. Throws when one is there and cannot be read. */
    public fun read(): T? {
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            return null
        }
        return json.decodeFromString(serializer, text)
    }

    public fun write(value: T) {
        SecureFile.writeAtomic0600(path, json.encodeToString(serializer, value))
    }
}

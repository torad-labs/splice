// NEW: failure-handling slice 4. The settings splice saved in its state folder's config.json, read whole and cleared
// one key at a time. ConfigService reads only the keys it knows, so a key it no longer knows stays in the file and
// nothing reads it; boot names such a key when it belonged to a retired knob, and the fix session clears it here.
package splice.core.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The saved settings in [file], as ConfigService writes them: one JSON object of knob keys. */
public class SavedSettings(private val file: Path) {
    /** Every key the file holds, known or not. None when the file is absent or holds no JSON object; ConfigService
     *  reports a file it cannot read itself. */
    public fun keys(): Set<String> = text()?.let(::parse)?.keys.orEmpty()

    /** [key] removed from the file, the rest kept as saved, and the file written whole and owner-only as ConfigService
     *  writes it. False when the file does not hold [key], or changed between the read and the write, since a running
     *  daemon can save a setting meanwhile and its save must not be lost; nothing is written then. */
    public fun clear(key: String): Boolean {
        val before = text()
        val saved = before?.let(::parse)
        if (saved == null || key !in saved) return false
        val after = Json.encodeToString(JsonObject.serializer(), JsonObject(saved - key)) + "\n"
        if (text() != before) return false
        SecureFile.writeAtomic0600(file, after)
        return true
    }

    private fun text(): String? = try {
        Files.readString(file)
    } catch (_: IOException) {
        null
    }

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

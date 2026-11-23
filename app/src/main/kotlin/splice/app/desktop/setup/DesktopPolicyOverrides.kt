// NEW: desktop-policy-console. The Claude Desktop policy values a person set in the console (Settings, Desktop
// policy), kept in `<state>/desktop-policy-overrides.json` (StatePaths.desktopPolicyOverridesFile), owner-only and
// written whole: one flat object of Desktop's keys. Only a key the console sets (DesktopPolicyCatalog) with a value of
// its kind is ever read back; anything else in the file is named as ignored and given to nobody.
package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DesktopPolicyCatalog
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What the overrides file holds now. */
internal sealed class PolicyOverridesRead {
    /** The overrides it holds, by key, and the keys it holds that the console does not set or that hold no value of
     *  their kind. No file is none of either. */
    data class Held(val values: JsonObject, val ignored: List<String>) : PolicyOverridesRead()

    /** A file that is no JSON object, or can't be read, and why: nothing of it is given to Desktop. */
    data class Unreadable(val why: String) : PolicyOverridesRead()
}

internal class DesktopPolicyOverrides(
    val file: Path,
    private val catalog: DesktopPolicyCatalog = DesktopPolicyCatalog(),
) {
    private val json = Json { prettyPrint = true }

    /** The overrides held now. */
    fun read(): PolicyOverridesRead {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return PolicyOverridesRead.Held(JsonObject(emptyMap()), emptyList())
        }
        val text = try {
            Files.readString(file)
        } catch (failure: IOException) {
            return PolicyOverridesRead.Unreadable("${file.fileName} can't be read (${SafeFailureText.render(failure)})")
        }
        return held(text)
    }

    /** [text], the file's, as the overrides it holds. */
    private fun held(text: String): PolicyOverridesRead {
        val held = JsonScalars.objectOrNull(Json, text)
            ?: return PolicyOverridesRead.Unreadable("${file.fileName} is not a JSON object")
        val (kept, ignored) = held.entries.partition { (key, value) -> usable(key, value) }
        return PolicyOverridesRead.Held(JsonObject(kept.associate { it.key to it.value }), ignored.map { it.key })
    }

    /** The overrides held now, or none when the file can't be read. */
    fun values(): JsonObject = (read() as? PolicyOverridesRead.Held)?.values ?: JsonObject(emptyMap())

    /** [values] written as the whole file, owner-only. */
    fun write(values: JsonObject) {
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonObject.serializer(), values) + "\n")
    }

    private fun usable(key: String, value: JsonElement): Boolean {
        val field = catalog.field(key) ?: return false
        return field.kind.refusal(value) == null
    }
}

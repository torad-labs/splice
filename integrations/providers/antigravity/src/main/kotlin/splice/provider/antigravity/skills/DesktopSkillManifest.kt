package splice.provider.antigravity.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopSkillManifest(private val log: LogSink) {
    /** The on-disk Desktop shape is a skills array; absent manifests and unlisted folders stay enabled. */
    fun disabledIn(plugin: Path): Set<String> {
        val file = plugin.resolve(MANIFEST_FILE)
        if (Files.notExists(file)) return emptySet()
        val entries = read(file)?.let { JsonScalars.objectOrNull(Json, it)?.get("skills") as? JsonArray }
        val disabled = entries?.let(::disabled)
        if (disabled != null) return disabled
        log("[antigravity] malformed Desktop skills manifest; using all plugin skills\n")
        return emptySet()
    }

    private fun read(file: Path): String? = try {
        SkillPaths.file(file)?.let { real ->
            val bytes = Files.newInputStream(real).use { it.readNBytes(DESKTOP_SKILL_MANIFEST_MAX_BYTES + 1) }
            bytes.takeIf { it.size <= DESKTOP_SKILL_MANIFEST_MAX_BYTES }?.decodeToString(throwOnInvalidSequence = true)
        }
    } catch (_: IOException) {
        null
    }

    private fun disabled(entries: JsonArray): Set<String>? {
        val names = mutableSetOf<String>()
        for (entry in entries) names.addAll(disabledNames(entry) ?: return null)
        return names
    }

    private fun disabledNames(element: JsonElement): List<String>? {
        val entry = element as? JsonObject ?: return null
        val names = NAME_FIELDS.map { JsonScalars.strIfString(entry[it]) }.filter(String::isNotBlank)
        val enabled = entry["enabled"]
        val flag = (enabled as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        return when {
            names.isEmpty() -> null
            enabled == null || flag == true -> emptyList()
            flag == false -> names
            else -> null
        }
    }
}

private const val MANIFEST_FILE = "manifest.json"

// why: allow large Desktop skill lists without handing unbounded JSON to the parser.
private const val DESKTOP_SKILL_MANIFEST_MAX_BYTES = 512 * 1024
private val NAME_FIELDS = listOf("name", "id", "skillId")

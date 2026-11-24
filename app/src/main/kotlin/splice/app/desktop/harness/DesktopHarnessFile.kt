// NEW: desktop-harnesses. `<state>/desktop-harnesses.json` (StatePaths.desktopHarnessesFile), owner-only and written
// whole: the harness map DesktopHarnesses resolves, which any Desktop session launcher reads, and the choices the
// person made in the console, which are the families it marks `"set_by": "console"`. Only those are read back; every
// other family is resolved again from splice's defaults and splice.toml at each read, so a default that changes in a
// later splice is never frozen into the file. A file this splice can't read is never written over: the person's
// choices in it stay where they are until they fix or move it.
package splice.app.desktop.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A family's harness, and whether its sessions run at all. */
internal data class HarnessChoice(val harness: DesktopHarness, val enabled: Boolean)

/** What the harness file holds now. */
internal sealed class HarnessFileRead {
    /** The console's choices in it, by family, and the families it marks as the console's that hold no valid choice.
     *  No file is none of either. */
    data class Held(val choices: Map<HarnessFamily, HarnessChoice>, val ignored: List<String>) : HarnessFileRead()

    /** A file that is not one this splice reads, and why: none of it is used, and nothing writes over it. */
    data class Unreadable(val why: String) : HarnessFileRead()
}

internal class DesktopHarnessFile(val file: Path) {
    private val json = Json { prettyPrint = true }
    private val words = HarnessWords()

    /** The console's choices held now. */
    fun read(): HarnessFileRead {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return HarnessFileRead.Held(emptyMap(), emptyList())
        val text = try {
            Files.readString(file)
        } catch (failure: IOException) {
            return HarnessFileRead.Unreadable("${file.fileName} can't be read (${SafeFailureText.render(failure)})")
        }
        return held(text)
    }

    /** The file as it reads now, or null when there is none or it can't be read: what a write would replace. */
    fun text(): String? = try {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.readString(file) else null
    } catch (_: IOException) {
        null
    }

    /** [document] written as the whole file, owner-only. */
    fun write(document: JsonObject) {
        SecureFile.writeAtomic0600(file, render(document))
    }

    /** [document] as the file spells it. */
    fun render(document: JsonObject): String = json.encodeToString(JsonObject.serializer(), document) + "\n"

    private fun held(text: String): HarnessFileRead {
        val name = file.fileName
        val held = JsonScalars.objectOrNull(Json, text)
            ?: return HarnessFileRead.Unreadable("$name is not a JSON object")
        val version = JsonScalars.str(held, HARNESS_KEY_VERSION)
        if (version != HARNESS_FILE_VERSION.toString()) {
            val said = version ?: "none"
            return HarnessFileRead.Unreadable("$name has version $said; this splice reads $HARNESS_FILE_VERSION")
        }
        val families = held[HARNESS_KEY_FAMILIES]
        return if (families == null || families is JsonArray) {
            chosen(families?.map { it as? JsonObject }.orEmpty())
        } else {
            HarnessFileRead.Unreadable("$name: `$HARNESS_KEY_FAMILIES` is not a list")
        }
    }

    /** The console's choices among [rows], the file's families; a row it marks as the console's that holds no valid
     *  choice, or names a family a second time, is named as ignored. */
    private fun chosen(rows: List<JsonObject?>): HarnessFileRead.Held {
        val choices = mutableMapOf<HarnessFamily, HarnessChoice>()
        val ignored = mutableListOf<String>()
        rows.forEachIndexed { at, row ->
            if (JsonScalars.str(row, HARNESS_KEY_SET_BY) != HarnessSetBy.CONSOLE.wire) return@forEachIndexed
            val family = words.family(JsonScalars.str(row, HARNESS_KEY_FAMILY))?.takeUnless { it in choices }
            val choice = family?.let { choiceOf(it, row) }
            if (family == null || choice == null) {
                ignored += JsonScalars.str(row, HARNESS_KEY_FAMILY) ?: "#$at"
            } else {
                choices[family] = choice
            }
        }
        return HarnessFileRead.Held(choices, ignored)
    }

    /** [row]'s choice for [family], or null when it names a harness [family] does not run on, or no switch. */
    private fun choiceOf(family: HarnessFamily, row: JsonObject?): HarnessChoice? {
        val harness = words.harness(JsonScalars.str(row, HARNESS_KEY_HARNESS))?.takeIf { it in family.choices }
        val enabled = switchOf(row?.get(HARNESS_KEY_ENABLED))?.takeIf { it || family.toggleable }
        return if (harness != null && enabled != null) HarnessChoice(harness, enabled) else null
    }

    private fun switchOf(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}

// What the file's schema is called by its readers: the one version this splice writes and reads.
internal const val HARNESS_FILE_VERSION = 1

internal const val HARNESS_KEY_VERSION = "version"
internal const val HARNESS_KEY_FAMILIES = "families"
internal const val HARNESS_KEY_FAMILY = "family"
internal const val HARNESS_KEY_HARNESS = "harness"
internal const val HARNESS_KEY_ENABLED = "enabled"
internal const val HARNESS_KEY_SET_BY = "set_by"

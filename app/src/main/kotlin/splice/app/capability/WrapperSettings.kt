// NEW: what splice says of Claude Code's managed settings for Claude Desktop's Code tab, in one shape for every place
// that says it: `splice desktop wrapper --print` and `--check`, the doctor's managed settings row and the upgrade item.
// It says what is wanted (both keys), what splice read (the file read, could not be read, or known to be absent, and
// each key in it text, not text, or absent), the other places Claude Code may take managed settings from, which splice
// names and does not read, and whether splice's launcher is laid. What Desktop does is said once, always as the
// condition: splice never reads every source, so it never says the effect plainly. What to do is to make the file hold
// both keys, in place wherever one may exist, after those places; only a file known to be absent is to be created. The
// places differ in form, never in what they claim.
package splice.app.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.ManagedFile
import splice.client.ManagedReading
import splice.client.ManagedValue
import splice.client.PARENT_SETTINGS_KEY
import splice.client.PARENT_SETTINGS_MERGE
import splice.client.PROCESS_WRAPPER_KEY
import splice.diagnostics.doctor.ToolLevel
import java.nio.file.Files
import java.nio.file.Path

/** What [reading] says of the managed settings Claude Desktop's Code tab needs, where splice's launcher is [launch]. */
internal class WrapperSettings(private val reading: ManagedReading, private val launch: Path) {
    val file: Path get() = reading.file
    val state: ManagedFile get() = reading.state
    private val read: ManagedFile.Read? get() = state as? ManagedFile.Read
    private val wrapper: ManagedValue get() = read?.value(PROCESS_WRAPPER_KEY) ?: ManagedValue.Absent
    private val merges: Boolean
        get() = read?.value(PARENT_SETTINGS_KEY) == ManagedValue.Text(PARENT_SETTINGS_MERGE)

    /** Whether splice's launcher is laid: a regular file splice may run. */
    val laid: Boolean = Files.isRegularFile(launch) && Files.isExecutable(launch)

    /** Whether the file splice read names splice's launcher as its processWrapper. */
    val starts: Boolean get() = wrapper == ManagedValue.Text(launch.toString())

    /** Whether the file splice read holds both keys as wanted. */
    val right: Boolean get() = starts && merges

    /** Both keys with the values wanted. */
    fun wanted(): JsonObject = JsonObject(
        mapOf(
            PROCESS_WRAPPER_KEY to JsonPrimitive(launch.toString()),
            PARENT_SETTINGS_KEY to JsonPrimitive(PARENT_SETTINGS_MERGE),
        ),
    )

    /** Both keys with the values wanted, in words. */
    fun wantedLine(): String = wanted().entries.joinToString(" and ", prefix = "wanted: ") { "${it.key} ${it.value}" }

    /** What splice found, each a sentence: the file, the places it did not read, the condition, and the launcher. */
    fun facts(): List<String> = listOfNotNull(found(), unread(), CONDITION, notLaid())

    /** What splice found at the file. */
    fun found(): String = when (state) {
        is ManagedFile.Read -> "$file ${phrase(PROCESS_WRAPPER_KEY)}, and ${phrase(PARENT_SETTINGS_KEY)}"
        ManagedFile.Unreadable ->
            "$file could not be read (no permission on its path, a link to nothing, or not a JSON object), so " +
                "splice does not say whether it exists or what it holds"
        ManagedFile.Absent -> "nothing is at $file"
    }

    /** The other places Claude Code may take managed settings from, which splice did not read; null where none. */
    fun unread(): String? = reading.unread.takeIf { it.isNotEmpty() }?.let { places ->
        "Claude Code may also take managed settings from ${places.joinToString(", ")}, which splice did not read: " +
            "a value set there may be the one Claude Code takes"
    }

    /** What to do, null where the file splice read holds both keys as wanted and splice names no other place. */
    fun step(): String? {
        val edit = when (state) {
            ManagedFile.Absent -> "create it so it holds both keys"
            ManagedFile.Unreadable ->
                "look at it as an administrator and make it hold both keys, editing it in place where it exists"
            is ManagedFile.Read -> IN_PLACE.takeUnless { right }
        }
        return when {
            reading.unread.isEmpty() -> edit
            edit == null -> CHECK_ONLY
            else -> "check the places splice did not read first; then $edit"
        }
    }

    /** [step], with where the keys are shown where the file is to change. */
    fun pointed(): String? = step()?.let { if (right) it else "$it (`splice desktop wrapper` shows what to put where)" }

    /** What is wanted and what was found, as one line. */
    fun said(): String = (listOf(wantedLine()) + facts()).joinToString("; ")

    /** How the doctor rates it: a warning where the file splice read does not say "merge" or names a wrapper that is
     *  not splice's laid launcher; a note otherwise, since which source wins is never known. */
    fun level(): ToolLevel = when {
        read == null || right && laid -> ToolLevel.INFO
        merges && wrapper == ManagedValue.Absent -> ToolLevel.INFO
        else -> ToolLevel.WARN
    }

    /** What setup names among what it found, in a few words; null where there is nothing to name. */
    fun detected(): String? = when {
        state == ManagedFile.Unreadable -> "a managed settings path splice could not read"
        starts -> "a managed settings file naming splice's desktop-launch as $PROCESS_WRAPPER_KEY"
        wrapper == ManagedValue.NotText -> "a managed settings file whose $PROCESS_WRAPPER_KEY is not text"
        wrapper is ManagedValue.Text -> "a managed settings file naming another program as $PROCESS_WRAPPER_KEY"
        else -> null
    }

    /** That splice's launcher is not laid, null where it is. */
    private fun notLaid(): String? = "$launch is not laid yet: run `splice desktop on` first".takeUnless { laid }

    private fun phrase(key: String): String = when (val value = read?.value(key) ?: ManagedValue.Absent) {
        is ManagedValue.Text -> "sets $key to ${JsonPrimitive(value.text)}"
        ManagedValue.NotText -> "sets $key to a value that is not text"
        ManagedValue.Absent -> "does not set $key"
    }
}

// What Desktop does: the condition, always true, and no more.
private const val CONDITION = "wherever managed settings exist, Desktop warns in every Code session unless the " +
    "source that wins sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\""

// What to do with a file splice read that lacks a key: never a copy, which would lose what the file is.
private const val IN_PLACE = "edit it in place as an administrator so it holds both keys; a copy put in its place " +
    "would lose its owner, its mode and its other keys"

// What to do with a file that holds both keys where splice names places it did not read.
private const val CHECK_ONLY = "check that none of the places splice did not read sets either key to another value"

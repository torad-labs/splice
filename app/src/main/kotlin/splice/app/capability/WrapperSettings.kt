// NEW: what splice says of Claude Code's managed settings for Claude Desktop's Code tab, in one shape for every place
// that says it: `splice desktop wrapper --print` and `--check`, the doctor's managed settings row and the upgrade item.
// It says what is wanted (both keys), what splice read (the main file read, could not be read, or known to be absent,
// and for each key the file whose value the merged files give, or that none of them sets it), the places Claude Code
// may take managed settings from that splice could not read, and whether splice's launcher is laid. What Desktop does
// is said once, as the condition, since a place splice did not read may win. What to do names the file to edit: a key
// held with another value is changed in the file that holds it, a key no file holds is added to the main file, each on
// the condition that no place splice did not read sets it. The places differ in form, never in what they claim.
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
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** What [reading] says of the managed settings Claude Desktop's Code tab needs, where splice's launcher is [launch]. */
internal class WrapperSettings(private val reading: ManagedReading, private val launch: Path) {
    val file: Path get() = reading.file
    val state: ManagedFile get() = reading.state
    private val values = mapOf(PROCESS_WRAPPER_KEY to launch.toString(), PARENT_SETTINGS_KEY to PARENT_SETTINGS_MERGE)

    /** Whether splice's launcher is laid: a regular file splice may run. */
    val laid: Boolean = Files.isRegularFile(launch) && Files.isExecutable(launch)

    /** Whether the files splice read, merged, name splice's launcher as their processWrapper. */
    val starts: Boolean get() = holds(PROCESS_WRAPPER_KEY)

    /** Whether the files splice read, merged, give both keys as wanted. */
    val right: Boolean get() = values.keys.all(::holds)

    /** Whether the main file is to be created whole: nothing is at its path and splice read no drop-in. */
    val creates: Boolean get() = state == ManagedFile.Absent && reading.read.isEmpty()

    /** The files to edit, each with the keys it is to hold; empty where both keys come out as wanted. */
    fun edits(): Map<Path, List<String>> = values.keys.filterNot(::holds).groupBy { reading.held(it)?.file ?: file }

    /** Both keys with the values wanted. */
    fun wanted(): JsonObject = JsonObject(values.mapValues { JsonPrimitive(it.value) })

    /** Both keys with the values wanted, in words. */
    fun wantedLine(): String = wanted().entries.joinToString(" and ", prefix = "wanted: ") { "${it.key} ${it.value}" }

    /** What splice found, each a sentence: the main file, each key, the places it did not read, the condition, and
     *  the launcher. */
    fun facts(): List<String> = listOfNotNull(mainState(), keys(), unread(), CONDITION, notLaid)

    /** The places Claude Code may take managed settings from that splice did not read; null where none. */
    fun unread(): String? = reading.unread.takeIf { it.isNotEmpty() }?.let { places ->
        "Claude Code may also take managed settings from ${places.joinToString(", ")}, which splice did not read: " +
            "a value set there may be the one Claude Code takes"
    }

    /** What to do: each file to edit and what it is to hold, on the condition that no place splice did not read sets
     *  those keys; where both keys come out as wanted, the check of the places splice did not read, or null if none. */
    fun step(): String? {
        val unseen = reading.unread.isNotEmpty()
        val edits = edits().takeIf { it.isNotEmpty() } ?: return CHECK_ONLY.takeIf { unseen }
        val keys = edits.values.flatten().joinToString(" or ")
        val changes = edits.entries.joinToString("; and ") { (target, keys) -> change(target, keys) }
        return if (unseen) "where none of the places splice did not read sets $keys, $changes" else changes
    }

    /** [step], with where the keys are shown and the restart that follows a change, where a file is to change. */
    fun pointed(): String? =
        step()?.let { if (right) it else "$it (`splice desktop wrapper` shows what to put where); $AFTER_CHANGE" }

    /** What is wanted and what was found, as one line. */
    fun said(): String = (listOf(wantedLine()) + facts()).joinToString("; ")

    /** How the doctor rates it: a warning where the files splice read do not give "merge" or name a wrapper that is
     *  not splice's laid launcher; a note otherwise, since a place splice did not read may win. */
    fun level(): ToolLevel = when {
        reading.read.isEmpty() || right && laid -> ToolLevel.INFO
        holds(PARENT_SETTINGS_KEY) && reading.held(PROCESS_WRAPPER_KEY) == null -> ToolLevel.INFO
        else -> ToolLevel.WARN
    }

    /** What setup names among what it found, in a few words; null where there is nothing to name. */
    fun detected(): String? {
        val wrapper = reading.held(PROCESS_WRAPPER_KEY)?.value
        return when {
            state == ManagedFile.Unreadable -> "a managed settings path splice could not read"
            state is ManagedFile.Unlistable -> "a managed settings folder splice could not list"
            starts -> "a managed settings file naming splice's desktop-launch as $PROCESS_WRAPPER_KEY"
            wrapper == ManagedValue.NotText -> "a managed settings file whose $PROCESS_WRAPPER_KEY is not text"
            wrapper is ManagedValue.Text -> "a managed settings file naming another program as $PROCESS_WRAPPER_KEY"
            else -> null
        }
    }

    private fun holds(key: String): Boolean = reading.held(key)?.value == ManagedValue.Text(values.getValue(key))

    /** What [target] is to hold, [keys] with their values, and how to get it there. */
    private fun change(target: Path, keys: List<String>): String {
        val held = keys.joinToString(" and ") { "$it ${JsonPrimitive(values.getValue(it))}" }
        return when {
            target != file || state is ManagedFile.Read ->
                "make $target hold $held, editing it in place as an administrator"
            state == ManagedFile.Absent -> "create $target holding $held"
            else -> "make $target hold $held as an administrator, editing it in place where it exists"
        }
    }

    /** The main file's own state where splice did not read it; null where it did. */
    private fun mainState(): String? = when (val current = state) {
        is ManagedFile.Read -> null
        ManagedFile.Unreadable ->
            "$file could not be read (no permission on its path, a link to nothing, or not a JSON object), so " +
                "splice does not say whether it exists or what it holds"
        is ManagedFile.Unlistable -> if (reading.read.isEmpty()) {
            "${current.folder} could not be listed, so splice could not check its contents or drop-ins"
        } else {
            "${current.folder} could not be listed, so splice could not check all of its contents"
        }
        ManagedFile.Absent -> "nothing is at $file"
    }

    /** For each key, the file that holds it and its value there, or that no file splice read sets it; null where
     *  splice read no file, which [mainState] says. */
    private fun keys(): String? = values.keys.takeIf { reading.read.isNotEmpty() }?.joinToString(", and ") { key ->
        when (val held = reading.held(key)) {
            null -> "no managed settings file splice read sets $key"
            else -> when (val value = held.value) {
                is ManagedValue.Text -> "${held.file} sets $key to ${JsonPrimitive(value.text)}"
                ManagedValue.NotText, ManagedValue.Absent -> "${held.file} sets $key to a value that is not text"
            }
        }
    }

    /** That splice's launcher is not laid, null where it is. */
    private val notLaid: String? get() = when {
        laid -> null
        Files.isDirectory(launch, NOFOLLOW_LINKS) ->
            "$launch is a directory, not splice's launcher: remove or move it, then run `splice desktop on`"
        Files.isRegularFile(launch) ->
            "$launch is not executable: run `splice desktop on` to refresh it"
        Files.exists(launch, NOFOLLOW_LINKS) ->
            "$launch is not a regular file: run `splice desktop on` to replace it"
        else -> "$launch is not laid yet: run `splice desktop on` first"
    }
}

// What Desktop does: the condition, always true, and no more.
private const val CONDITION = "wherever managed settings exist, Desktop warns in every Code session unless the " +
    "source that wins sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\""

// Desktop reads its configuration at launch, so a change reaches it only after the whole app restarts.
internal const val AFTER_CHANGE = "after a change, quit Claude Desktop entirely and reopen it"

// What to do with files that give both keys where splice names places it did not read.
private const val CHECK_ONLY = "check that none of the places splice did not read sets either key to another value"

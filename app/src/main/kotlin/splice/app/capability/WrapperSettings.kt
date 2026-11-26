// NEW: what splice says of Claude Code's managed settings for Claude Desktop's Code tab, in one shape for every place
// that says it: `splice desktop wrapper --print` and `--check`, the doctor's managed settings row and the upgrade item.
// It says what is wanted (both keys), what splice read (the file read, could not be read, or known to be absent, and
// each key in it text, not text, or absent), every other place Claude Code reads that splice did not read or could not
// look at, and whether splice's launcher is laid. What Desktop does is said once: plainly only where splice read the
// file and there is no other place, and as a condition otherwise. What to do is to make the file hold both keys, in
// place wherever one may exist, after the places splice did not read; only a file known to be absent is to be created.
// The places differ in form, never in what they claim.
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

    /** Whether splice read the file and there is no other place Claude Code may take managed settings from. */
    private val known: Boolean get() = read != null && reading.unread.none

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

    /** What splice found, each a sentence: the file, the places it did not read, the effect, and the launcher. */
    fun facts(): List<String> = listOfNotNull(found(), unread(), effect(), notLaid())

    /** What splice found at the file. */
    fun found(): String = when (state) {
        is ManagedFile.Read -> "$file ${phrase(PROCESS_WRAPPER_KEY)}, and ${phrase(PARENT_SETTINGS_KEY)}"
        ManagedFile.Unreadable ->
            "$file could not be read (no permission on its path, a link to nothing, or not a JSON object), so " +
                "splice does not say whether it exists or what it holds"
        ManagedFile.Absent -> "nothing is at $file"
    }

    /** The places Claude Code also reads that splice did not read, or could not look at; null where there are none. */
    fun unread(): String? {
        val places = reading.unread.takeUnless { it.none } ?: return null
        val held = places.held.takeIf { it.isNotEmpty() }?.let { "Claude Code also reads ${it.joinToString(", ")}" }
        val unsure = places.unsure.takeIf { it.isNotEmpty() }
            ?.let { "splice could not look at ${it.joinToString(", ")}, where Claude Code may find more" }
        return listOfNotNull(held, unsure).joinToString(", and ") + ": $UNREAD"
    }

    /** What Desktop does: plainly only where splice read the file and there is no other place, the condition else;
     *  nothing where the file names splice's launcher and it is not laid, since no session starts through it then. */
    fun effect(): String? = when {
        !known -> CONDITION
        starts && !laid -> null
        merges -> "Claude Desktop's limits apply to the Code sessions it starts, and Desktop does not warn in them"
        else -> "Desktop warns in every Code session, and its limits for them are not applied"
    }

    /** What to do, null where the file splice read holds both keys as wanted and there is no other place. */
    fun step(): String? {
        val edit = when (state) {
            ManagedFile.Absent -> "create it so it holds both keys"
            ManagedFile.Unreadable ->
                "look at it as an administrator and make it hold both keys, editing it in place where it exists"
            is ManagedFile.Read -> IN_PLACE.takeUnless { right }
        }
        return when {
            reading.unread.none -> edit
            edit == null -> "check that none of the places splice did not read sets either key to another value"
            else -> "check the places splice did not read first; then $edit"
        }
    }

    /** [step], with where the keys are shown where the file is to change. */
    fun pointed(): String? = step()?.let { if (right) it else "$it (`splice desktop wrapper` shows what to put where)" }

    /** What is wanted and what was found, as one line. */
    fun said(): String = (listOf(wantedLine()) + facts()).joinToString("; ")

    /** How the doctor rates it: a warning only where splice read every place and something is known to be wrong. */
    fun level(): ToolLevel = when {
        !known -> ToolLevel.INFO
        right && laid -> ToolLevel.OK
        !right && merges && wrapper == ManagedValue.Absent -> ToolLevel.INFO
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

// What a place splice did not read means for what it says.
private const val UNREAD = "splice did not read them, and a value set there may be the one Claude Code takes"

// What Desktop does where splice did not read every place: the condition, always true, and no more.
private const val CONDITION = "wherever managed settings exist, Desktop warns in every Code session unless the " +
    "source that wins sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\""

// What to do with a file splice read that lacks a key: never a copy, which would lose what the file is.
private const val IN_PLACE = "edit it in place as an administrator so it holds both keys; a copy put in its place " +
    "would lose its owner, its mode and its other keys"

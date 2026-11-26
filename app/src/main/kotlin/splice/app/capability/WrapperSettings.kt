// NEW: what splice says of Claude Code's managed settings for Claude Desktop's Code tab, in one shape for every place
// that says it: `splice desktop wrapper --print` and `--check`, the doctor's managed settings row and the upgrade item.
// It says what is wanted (both keys), what splice read (the file read, could not be read, or known to be absent, and
// each key in it text, not text, or absent), and every other place Claude Code reads that splice found and did not
// read. What Desktop does is said once: plainly only where splice read every place there is, and as a condition
// otherwise. What to do is to make the file hold both keys, in place wherever one may exist, after the places splice
// did not read; only a file known to be absent is to be created. The places differ in form, never in what they claim.
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
import java.nio.file.Path

/** What [reading] says of the managed settings Claude Desktop's Code tab needs, where splice's launcher is [launch]. */
internal class WrapperSettings(private val reading: ManagedReading, private val launch: Path) {
    val file: Path get() = reading.file
    val state: ManagedFile get() = reading.state
    private val read: ManagedFile.Read? get() = state as? ManagedFile.Read
    private val wrapper: ManagedValue get() = read?.value(PROCESS_WRAPPER_KEY) ?: ManagedValue.Absent
    private val merges: Boolean
        get() = read?.value(PARENT_SETTINGS_KEY) == ManagedValue.Text(PARENT_SETTINGS_MERGE)

    /** Whether splice read every place there is: the file read or known to be absent, and no other source found. */
    val known: Boolean get() = state != ManagedFile.Unreadable && reading.unread.isEmpty()

    /** Whether the file splice read names splice's launcher as its processWrapper. */
    val starts: Boolean get() = wrapper == ManagedValue.Text(launch.toString())

    /** Both keys with the values wanted. */
    fun wanted(): JsonObject = JsonObject(
        mapOf(
            PROCESS_WRAPPER_KEY to JsonPrimitive(launch.toString()),
            PARENT_SETTINGS_KEY to JsonPrimitive(PARENT_SETTINGS_MERGE),
        ),
    )

    /** What splice found at the file. */
    fun found(): String = when (state) {
        is ManagedFile.Read -> "$file ${phrase(PROCESS_WRAPPER_KEY)}, and ${phrase(PARENT_SETTINGS_KEY)}"
        ManagedFile.Unreadable ->
            "$file could not be read (no permission on its path, a link to nothing, or not a JSON object), so " +
                "splice does not say whether it exists or what it holds"
        ManagedFile.Absent -> "nothing is at $file"
    }

    /** The places Claude Code also reads that splice found and did not read, null where there are none. */
    fun unread(): String? = reading.unread.takeIf { it.isNotEmpty() }?.let {
        "Claude Code also reads ${it.joinToString(", ")}, which splice did not read: a value set there may be the " +
            "one Claude Code takes"
    }

    /** What Desktop does, said plainly only where splice read every place there is, and as the condition otherwise. */
    fun effect(): String = when {
        !known -> UNKNOWN_EFFECT
        read == null -> "Claude Code reads no managed settings here, so Desktop does not warn in its Code sessions"
        merges -> "${launched()}; Claude Desktop's limits apply to the Code sessions it starts, and Desktop does not " +
            "warn in them"
        else -> "${launched()}; Desktop warns in every Code session, and its limits for them are not applied"
    }

    /** What to do, null where the file splice read holds both keys as wanted. */
    fun step(): String? {
        val edit = when (state) {
            ManagedFile.Absent ->
                "to have the Code tab start through splice (Antigravity there, live model switches), create it so " +
                    "it holds both keys"
            ManagedFile.Unreadable ->
                "look at it as an administrator and make it hold both keys, editing it in place where it exists"
            is ManagedFile.Read -> IN_PLACE.takeUnless { starts && merges }
        }
        return when {
            reading.unread.isEmpty() -> edit
            edit == null -> "check that none of the places splice did not read sets either key to another value"
            else -> "check the places splice did not read first; then $edit"
        }
    }

    /** [step] with where the keys are shown. */
    fun pointed(): String? = step()?.let { "$it (`splice desktop wrapper` shows what to put where)" }

    /** What was found, the places not read, and the effect, as one line. */
    fun said(): String = listOfNotNull(found(), unread(), effect()).joinToString("; ")

    /** How the doctor rates it: a warning only where splice read every place and something is known to be wrong. */
    fun level(): ToolLevel = when {
        !known || read == null -> ToolLevel.INFO
        starts && merges -> ToolLevel.OK
        merges && wrapper == ManagedValue.Absent -> ToolLevel.INFO
        else -> ToolLevel.WARN
    }

    /** What setup names among what it found, in a few words; null where there is nothing to name. */
    fun detected(): String? = when {
        state == ManagedFile.Unreadable -> "a managed settings file splice could not read"
        starts -> "a managed settings file naming splice's desktop-launch as $PROCESS_WRAPPER_KEY"
        wrapper == ManagedValue.NotText -> "a managed settings file whose $PROCESS_WRAPPER_KEY is not text"
        wrapper is ManagedValue.Text -> "a managed settings file naming another program as $PROCESS_WRAPPER_KEY"
        else -> null
    }

    /** What starts the Code tab by the file splice read, where it read every place there is. */
    private fun launched(): String = when (val value = wrapper) {
        ManagedValue.Absent ->
            "no $PROCESS_WRAPPER_KEY is named, so Antigravity and live model switches are not offered in the Code tab"
        ManagedValue.NotText -> "splice cannot say what starts the Code tab"
        is ManagedValue.Text -> if (starts) {
            "the Code tab starts through splice's launcher"
        } else {
            "the Code tab starts through ${value.text}, and Desktop passes it no key for splice"
        }
    }

    private fun phrase(key: String): String = when (val value = read?.value(key) ?: ManagedValue.Absent) {
        is ManagedValue.Text -> "sets $key to ${JsonPrimitive(value.text)}"
        ManagedValue.NotText -> "sets $key to a value that is not text"
        ManagedValue.Absent -> "does not set $key"
    }
}

// What Desktop does where splice did not read every place: the condition, always true, and no more.
private const val UNKNOWN_EFFECT = "splice does not say what Desktop does, because it did not read every place " +
    "Claude Code reads: wherever managed settings exist, Desktop warns in every Code session unless the source that " +
    "wins sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\""

// What to do with a file splice read that lacks a key: never a copy, which would lose what the file is.
private const val IN_PLACE = "edit it in place as an administrator so it holds both keys; a copy put in its place " +
    "would lose its owner, its mode and its other keys"

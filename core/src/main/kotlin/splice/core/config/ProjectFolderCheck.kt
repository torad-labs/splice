// NEW: Oct 11, 2026 — a Projects folder is a display preference and never stops splice (Marlin's ruling). A path TYPED
// into Settings that is not a readable folder is refused at Save, naming its entry. A path already saved that has since
// vanished is not checked here: boot keeps it and the projects read reports it as not found.
package splice.core.config

import splice.core.util.PathProbe
import java.nio.file.Files

public object ProjectFolderCheck {
    /** Whether [entry] names a place from the root: an absolute path, or one from the home as `~` or `~/...`. */
    public fun absolute(entry: String): Boolean =
        entry.trim().let { it.startsWith("/") || it == "~" || it.startsWith("~/") }

    /** [entry] as the filesystem spells it: a leading `~` is the home folder, any other spelling is kept. The one
     *  expansion boot, Save and the resolver share, so they agree on which folder was typed. */
    public fun expanded(entry: String): String = entry.trim().let { typed ->
        when {
            typed == "~" -> UserHome.dir().toString()
            typed.startsWith("~/") -> UserHome.dir().resolve(typed.substring(2)).toString()
            else -> typed
        }
    }

    /** Whether [folder], spelled as typed, is a directory this process can read. */
    public fun found(folder: String): Boolean =
        PathProbe.spelled(expanded(folder))?.let { Files.isDirectory(it) && Files.isReadable(it) } == true

    /** The sentence naming the first entry of the colon-separated [raw] that is not a readable folder, or null. */
    public fun refusal(raw: String): String? =
        raw.split(':').map { it.trim() }.withIndex().firstOrNull { (_, entry) -> !found(entry) }
            ?.let { (index, _) -> "entry ${index + 1} is not a folder this process can read" }

    /** [refusal] when the PATCH of [raw] is to the Projects folder knob, else null: every other knob is not asked. */
    public fun refusalOn(knob: Knob?, raw: String?): String? =
        if (knob == Knob.PROJECTS_FOLDERS && raw != null) refusal(raw) else null
}

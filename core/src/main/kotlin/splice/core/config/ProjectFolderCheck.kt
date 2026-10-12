// NEW: Oct 11, 2026 — a Projects folder is a display preference and never stops splice (Marlin's ruling). A path TYPED
// into Settings that is not a readable folder is refused at Save, naming its entry. A path already saved that has since
// vanished is not checked here: boot keeps it and the projects read reports it as not found.
package splice.core.config

import splice.core.util.PathProbe
import java.nio.file.Files

public object ProjectFolderCheck {
    /** Whether [folder] is a directory this process can read. */
    public fun found(folder: String): Boolean =
        PathProbe.spelled(folder)?.let { Files.isDirectory(it) && Files.isReadable(it) } == true

    /** The sentence naming the first entry of the colon-separated [raw] that is not a readable folder, or null. */
    public fun refusal(raw: String): String? =
        raw.split(':').map { it.trim() }.withIndex().firstOrNull { (_, entry) -> !found(entry) }
            ?.let { (index, _) -> "entry ${index + 1} is not a folder this process can read" }

    /** [refusal] when the PATCH of [raw] is to the Projects folder knob, else null: every other knob is not asked. */
    public fun refusalOn(knob: Knob?, raw: String?): String? =
        if (knob == Knob.PROJECTS_FOLDERS && raw != null) refusal(raw) else null
}

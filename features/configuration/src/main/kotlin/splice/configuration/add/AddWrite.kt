// NEW: V4-220 item 3 (2026-09-25) — the only write an add makes, shared by `splice add`, the console's
// add and add-model on both surfaces, moved out of AddCommand.save so none of them can write differently.
package splice.configuration.add

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Whether the candidate's tables reached the file. */
internal sealed class AddWritten {
    data object Written : AddWritten()

    /** Nothing written (AddRefusalText renders why, per surface). */
    sealed class Refused : AddWritten()

    /** The file changed since the candidate was built from it. */
    data object Changed : Refused()

    /** The file could not be read again; [detail] is SafeFailureText's. */
    data class Unreadable(val detail: String) : Refused()
}

internal class AddWrite {

    /** A sibling temp file, then ONE rename — the previous file is intact until then. The candidate
     *  was built from [AddCandidate.existing]; a sign-in and the checks ran since, so the file is read
     *  again first and a change in between (an editor, a second add) refuses the write instead of being
     *  overwritten by a rename. A file that cannot be read again (deleted, replaced by something
     *  unreadable) is refused the same way: the candidate was built from a file that existed, so a
     *  rename that recreated it would write stale content (review 2026-09-14). */
    fun write(c: AddCandidate): AddWritten = replace(c.path, c.existing, c.existing + c.appended)

    /** [composed] renamed over [path] only while the file still holds [existing]: the same re-read and
     *  rename as [write], for add-model's roster edit, which changes the middle of the file rather than
     *  appending (V4-220: add-model read the file before its prompts and renamed over any edit since). */
    fun replace(path: Path, existing: String, composed: String): AddWritten {
        // Normalized the way the candidate's `existing` was (one trailing newline), or a config saved
        // without one would be "changed" on every run and never written (review 2026-09-14).
        val stale = Cancellables.runCatchingCancellable { normalized(Files.readString(path)) }.fold(
            onSuccess = { if (it == normalized(existing)) null else AddWritten.Changed },
            onFailure = { AddWritten.Unreadable(SafeFailureText.render(it)) },
        )
        if (stale != null) return stale
        val tmp = path.resolveSibling(path.fileName.toString() + ".add-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, composed)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return AddWritten.Written
    }

    private fun normalized(text: String): String = text.trimEnd('\n') + "\n"
}

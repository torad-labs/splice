// NEW: V4-220 item 3 (2026-09-25) — the only write an add makes, shared by `splice add` and the
// console's add, moved out of AddCommand.save unchanged so the two cannot write differently.
package splice.configuration.add

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Whether the candidate's tables reached the file. */
internal sealed class AddWritten {
    data object Written : AddWritten()

    /** Nothing written; [stale] says why, after the file's path. */
    data class Refused(val stale: String) : AddWritten()
}

internal class AddWrite {

    /** A sibling temp file, then ONE rename — the previous file is intact until then. The candidate
     *  was built from [AddCandidate.existing]; a sign-in and the checks ran since, so the file is read
     *  again first and a change in between (an editor, a second add) refuses the write instead of being
     *  overwritten by a rename. A file that cannot be read again (deleted, replaced by something
     *  unreadable) is refused the same way: the candidate was built from a file that existed, so a
     *  rename that recreated it would write stale content (review 2026-09-14). */
    fun write(c: AddCandidate): AddWritten {
        // Normalized the way the candidate's `existing` was (one trailing newline), or a config saved
        // without one would be "changed" on every run and never written (review 2026-09-14).
        val stale = Cancellables.runCatchingCancellable { Files.readString(c.path).trimEnd('\n') + "\n" }.fold(
            onSuccess = { if (it == c.existing) null else "changed while this add was running — rerun" },
            onFailure = { "could not be read again (${SafeFailureText.render(it)}) — nothing written" },
        )
        if (stale != null) return AddWritten.Refused(stale)
        val tmp = c.path.resolveSibling(c.path.fileName.toString() + ".add-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, c.existing + c.appended)
        Files.move(tmp, c.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return AddWritten.Written
    }
}

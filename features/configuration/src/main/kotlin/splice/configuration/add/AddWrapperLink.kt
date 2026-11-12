// NEW: V4-220 item 3 (2026-09-25) — linking a saved head's wrapper, as `splice add` and the console's
// add both do after the save, in one place so both report a refused link the same way.
//
// The linker answers a refusal as a value, its own sentence, and the seam hands it back as AddLinked.NotLinked. What
// escapes it is a failure nobody composed a sentence for: the link runs under runCatchingCleanup so that one cannot
// escape `splice add` AFTER the save wrote the head instead of printing the `splice install` line, and it is
// rendered through SafeFailureText, which withholds any message that may quote file bytes.
package splice.configuration.add

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText

/** Whether the wrapper was linked; [why] is the linker's own reason when it gave one. */
public sealed class AddLinked {
    public data object Linked : AddLinked()

    public data class NotLinked(val why: String?) : AddLinked()
}

internal class AddWrapperLink(private val install: WrapperInstall) {
    fun link(key: String, env: EnvReader): AddLinked =
        Cancellables.runCatchingCleanup { install(key, env) }
            .getOrElse { AddLinked.NotLinked(SafeFailureText.render(it)) }
}

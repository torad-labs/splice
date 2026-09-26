// NEW: V4-220 item 3 (2026-09-25) — linking a saved head's wrapper, as `splice add` and the console's
// add both do after the save, in one place so both report a refused link the same way.
//
// The linker refuses with InstallRefused, an IllegalStateException, and the CLI caught the link with
// runCatchingCancellable, which lets an IllegalStateException through: a wrapper name another file
// already held escaped `splice add` AFTER the save had written the head, instead of printing the
// `splice install` line. runCatchingCleanup is the catch that includes it.
//
// V4-255: the reason is the install seam's own rendering (WrapperInstall.refusalText). Rendering every
// failure through SafeFailureText here withheld InstallRefused's sentence, an IllegalStateException, so
// a missing launch shim read "failure (message withheld: it may quote file bytes)".
package splice.configuration.add

import splice.core.util.Cancellables
import splice.core.util.EnvReader

/** Whether the wrapper was linked; [why] is the linker's own reason when it gave one. */
internal sealed class AddLinked {
    data object Linked : AddLinked()

    data class NotLinked(val why: String?) : AddLinked()
}

internal class AddWrapperLink(private val install: WrapperInstall) {
    fun link(key: String, env: EnvReader): AddLinked {
        val link = Cancellables.runCatchingCleanup { install(key, env) }
        return when {
            link.getOrElse { false } -> AddLinked.Linked
            else -> AddLinked.NotLinked(link.exceptionOrNull()?.let(install::refusalText))
        }
    }
}

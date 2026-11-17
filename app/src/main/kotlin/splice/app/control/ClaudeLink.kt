// NEW: door-default (docs/specs/door-default.md, decisions 1 and 8). `splice claude door` puts the terminal's `claude`
// on the door, and a `claude` that is not linked to splice never asks splice how to launch, so the move links it the
// way `splice setup` does: the daemon's one wrap (LaunchService.wrap, the one /api/claude-head/wrap acts on), which
// swaps the `claude` on PATH for splice's launcher and writes nothing in ~/.claude. A `claude` already linked is left
// as it is, and a link the machine refuses is said in its own words, beside the move, which still holds.
package splice.app.control

import splice.client.wrap.WrapResult
import splice.client.wrap.WrappedHead

/** The daemon's wrap of `claude`, read at each use; null on a control plane that launches nothing. */
internal fun interface DaemonWrap {
    operator fun invoke(): WrappedHead?
}

internal class ClaudeLink(private val wrap: DaemonWrap) {

    /** [said], with what linking `claude` did; unchanged where it was already linked or nothing here links it. */
    fun linked(said: String): String {
        val head = wrap() ?: return said
        if (head.status().mode == WRAPPED) return said
        return when (val result = head.wrap()) {
            is WrapResult.Ok -> "$said; `claude` is linked to splice now"
            is WrapResult.Refused -> "$said; `claude` is not linked to splice, so it runs as before (${result.reason})"
        }
    }
}

// What WrappedHead's status says while `claude` on PATH is splice's launcher.
private const val WRAPPED = "wrapped"

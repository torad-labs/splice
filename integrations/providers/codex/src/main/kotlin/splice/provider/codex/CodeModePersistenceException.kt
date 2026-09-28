// NEW: distinguishes local durable-state failures from upstream or JavaScript runtime failures.
package splice.provider.codex

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.util.SafeFailureText
import java.io.IOException

internal class CodeModePersistenceException(cause: IOException, diskFull: Boolean) :
    IOException(cause) {
    /** V4-397: the reason in words. A full disk throws a plain IOException, whose text
     *  SafeFailureText withholds, so the store's own free-space reading names it. */
    override val message: String = if (diskFull) {
        "code-mode state could not be saved: the disk that holds splice's state is full, " +
            "so free space on it; source was not rerun"
    } else {
        "code-mode state could not be saved (${SafeFailureText.render(cause)}); source was not rerun"
    }

    fun outcome(): TurnOutcome.Failure = TurnOutcome.Failure(
        message,
        // V4-117: CODE_MODE_PROTOCOL — the state file we wrote could not be read back, and the
        // source is deliberately NOT rerun. No upstream layer can repair a local persistence fault.
        cause = FailureCause.CODE_MODE_PROTOCOL,
        phase = FailurePhase.MID_OUTPUT,
    )
}

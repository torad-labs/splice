// NEW: distinguishes local durable-state failures from upstream or JavaScript runtime failures.
package splice.provider.codex

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import java.io.IOException

internal class CodeModePersistenceException(cause: IOException) :
    IOException("code-mode state persistence failed; source was not rerun", cause) {
    fun outcome(): TurnOutcome.Failure = TurnOutcome.Failure(
        checkNotNull(message),
        // V4-117: CODE_MODE_PROTOCOL — the state file we wrote could not be read back, and the
        // source is deliberately NOT rerun. No upstream layer can repair a local persistence fault.
        cause = FailureCause.CODE_MODE_PROTOCOL,
        phase = FailurePhase.MID_OUTPUT,
    )
}

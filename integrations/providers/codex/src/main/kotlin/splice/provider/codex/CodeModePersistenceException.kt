// NEW: distinguishes local durable-state failures from upstream or JavaScript runtime failures.
package splice.provider.codex

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.memory.HeapCapacityException
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.SafeFailureText
import splice.upstream.codemode.CodeModeSourcePersistenceException
import java.io.IOException

internal class CodeModePersistenceException(cause: IOException, diskFull: Boolean) :
    CodeModeSourcePersistenceException(cause) {
    /** V4-397: the reason in words. A full disk throws a plain IOException, whose text
     *  SafeFailureText withholds, so the store's own free-space reading names it. */
    override val message: String = when {
        cause is HeapCapacityException ->
            "code-mode state could not be saved: the daemon heap reservation was refused; source was not rerun"
        diskFull ->
            "code-mode state could not be saved: the disk that holds splice's state is full, " +
                "so free space on it; source was not rerun"
        else -> "code-mode state could not be saved (${SafeFailureText.render(cause)}); source was not rerun"
    }

    fun outcome(): TurnOutcome.Failure = TurnOutcome.Failure(
        // V4-117: CODE_MODE_PROTOCOL — the state file we wrote could not be read back, and the
        // source is deliberately NOT rerun. No upstream layer can repair a local persistence fault,
        // and a later turn may find the disk or the heap free again.
        cause = FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_START),
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(message),
        salvagedUsage = noRequestUsage,
    )
}

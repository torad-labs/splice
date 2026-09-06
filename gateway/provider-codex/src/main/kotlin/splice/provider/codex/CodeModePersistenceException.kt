// NEW: distinguishes local durable-state failures from upstream or JavaScript runtime failures.
package splice.provider.codex

import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import java.io.IOException

internal class CodeModePersistenceException(cause: IOException) :
    IOException("code-mode state persistence failed; source was not rerun", cause) {
    fun outcome(): TurnOutcome.Failure = TurnOutcome.Failure(ErrorType.API_ERROR, checkNotNull(message))
}

// NEW: preserve proven source transport attribution across an incidentally closed runtime cell.
package splice.provider.codex.stream

import splice.core.turn.TurnOutcome
import java.io.IOException

/** A proven upstream source tear, never an identity, persistence or JavaScript protocol failure. [verdict] is the
 *  round's permanent upstream failure, when one ended it, which the step ends with as it is. */
internal class CodeModeSourceInterruptedException(val verdict: TurnOutcome.Failure? = null) :
    IOException("upstream source interrupted; source was not rerun")

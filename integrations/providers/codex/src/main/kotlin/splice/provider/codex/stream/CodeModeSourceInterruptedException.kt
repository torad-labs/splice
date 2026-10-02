// NEW: preserve proven source transport attribution across an incidentally closed runtime cell.
package splice.provider.codex.stream

import java.io.IOException

/** A proven upstream source tear, never an identity, persistence or JavaScript protocol failure. */
internal class CodeModeSourceInterruptedException : IOException("upstream source interrupted; source was not rerun")

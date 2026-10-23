// NEW: source completion and source interruption have distinct execution outcomes.
package splice.upstream.codemode

import java.io.IOException

/** A live model item. Reading waits for the whole item, or the failure that ended it, without owning a client
 *  connection. */
public fun interface CodeModeSource {
    public suspend fun read(): CodeModeSourcePart
}

/** A local source commit failed. Runtimes propagate this category rather than turning it into a source terminal. */
public abstract class CodeModeSourcePersistenceException(cause: IOException) : IOException(cause)

/** A complete item carries its entire source, preserving ordinary whole-script semantics. */
public sealed class CodeModeSourcePart {
    public data class Complete(val text: String = "") : CodeModeSourcePart()
    public data class Failed(val error: String) : CodeModeSourcePart()
}

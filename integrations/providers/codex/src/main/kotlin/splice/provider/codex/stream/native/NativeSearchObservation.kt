package splice.provider.codex.stream.native

import splice.upstream.codemode.CodeModeStep

/** The cell's next step as one observation reads it, with or without a yield limit; the search cell answers its
 *  local tool_search calls between steps. */
internal fun interface NativeSearchObservation {
    suspend fun next(): CodeModeStep
}

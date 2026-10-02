// NEW: source completion and source interruption have distinct execution outcomes.
package splice.upstream.codemode

/** A live model item. Reading waits for the next source delta without owning a client connection. */
public fun interface CodeModeSource {
    public suspend fun read(): CodeModeSourcePart
}

/**
 * A producer commitment to a fixed native namespace during streaming.
 * The runtime rejects later function-body declarations of these names without replaying executed source.
 * Unsealed intrinsics remain deferred until the complete body establishes its native binding semantics.
 */
public interface CodeModeSealedSource : CodeModeSource {
    public val sealedGlobals: Set<String>
}

/** A complete item may contain its entire source, preserving ordinary whole-script semantics. */
public sealed class CodeModeSourcePart {
    public data class Delta(val text: String) : CodeModeSourcePart()
    public data class Complete(val text: String = "") : CodeModeSourcePart()
    public data class Failed(val error: String) : CodeModeSourcePart()
}

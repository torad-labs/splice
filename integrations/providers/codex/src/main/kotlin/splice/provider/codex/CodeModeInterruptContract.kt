package splice.provider.codex

/** An explicitly addressed native cell and its actual termination outcome. Session storage remains live. */
public data class CodeModeInterruptedCell(
    val cellId: String,
    val terminated: Boolean,
    val failure: String? = null,
)

/** Distinguishes an owned idle conversation from one that is absent or belongs to a different client. */
public sealed class CodeModeInterruptOutcome {
    public data class Interrupted(val cells: List<CodeModeInterruptedCell>) : CodeModeInterruptOutcome()
    public data object Unavailable : CodeModeInterruptOutcome()
}

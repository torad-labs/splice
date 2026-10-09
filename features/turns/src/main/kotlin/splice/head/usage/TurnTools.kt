package splice.head.usage

/** The tool-surface partition one turn reported. Both are null on a head whose dialect cannot defer. */
public data class TurnTools(
    val toolsEager: Long?,
    val toolsDeferred: Long?,
)

package splice.core.turn

/** The head's standing system prompt and its provenance (mode, and the file when one backs it)
 *  on every turn it was placed on — unlike [TurnCompaction], which is compact-only. Both stay null
 *  for a head that configures none, and for a dialect that could not place it (the [source] then
 *  carries the " (not applied)" suffix). */
public data class TurnSystemPrompt(
    val text: String? = null,
    val source: String? = null,
)

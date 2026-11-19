// NEW: the rule splice calls a capability by (docs/specs/harness.md, decision 1): the `when` table and the values each
// of its keys takes, as splice.toml spells them.
package splice.core.capability

/** The `when` table: a request has to meet all four. [models] null is every model. */
public data class CapabilityRule(
    val turn: CapabilityTurn = CapabilityTurn.FIRST,
    val thread: CapabilityThread = CapabilityThread.MAIN,
    val mode: CapabilityMode = CapabilityMode.INTERACTIVE,
    val models: List<String>? = null,
)

/** `when.turn`: the conversation's first turn, or every turn. */
public enum class CapabilityTurn(public val wire: String) { FIRST("first"), ANY("any") }

/** `when.thread`: the main conversation, a subagent's, or either. */
public enum class CapabilityThread(public val wire: String) { MAIN("main"), SUBAGENT("subagent"), ANY("any") }

/** `when.mode`: an interactive session, a print-mode run, or either. */
public enum class CapabilityMode(public val wire: String) { INTERACTIVE("interactive"), PRINT("print"), ANY("any") }

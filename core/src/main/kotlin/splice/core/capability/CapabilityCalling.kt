// NEW: who may call a capability (docs/specs/harness.md, decision 12): splice by its rule, the model through the tool
// the capability is offered as, or either, with the tool's name, purpose and input schema as splice.toml declares them.
package splice.core.capability

import kotlinx.serialization.json.JsonObject

/** `call`: splice by its rule, the model through the tool it is offered (decision 12), or either. */
public enum class CapabilityCaller(public val wire: String) {
    RULE("rule"),
    AGENT("agent"),
    BOTH("both"),
    ;

    /** Whether splice calls it by the rule in `when`. */
    public val byRule: Boolean get() = this != AGENT

    /** Whether the model may call it, through its tool. */
    public val byAgent: Boolean get() = this != RULE
}

/** Who may call a capability, the [rule] splice calls it by and offers its tool by, and the [tool] the model calls it
 *  through: none for one only its rule calls. */
public data class CapabilityCalling(
    val caller: CapabilityCaller = CapabilityCaller.RULE,
    val rule: CapabilityRule = CapabilityRule(),
    val tool: CapabilityTool? = null,
) {
    /** Whether splice calls it by the rule in `when`. */
    public val byRule: Boolean get() = caller.byRule

    /** Whether the model may call it, through its tool. */
    public val byAgent: Boolean get() = caller.byAgent
}

/** The tool a capability the model may call is offered as: its name, what it is for as the model reads it, and the
 *  JSON schema of its input, as splice.toml declares them. */
public data class CapabilityTool(val name: String, val description: String, val input: JsonObject)

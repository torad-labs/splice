// NEW: the keys of a capability the model may call (docs/specs/harness.md, decisions 1 and 12): `call`, and the `tool`
// it is offered as. A tool belongs to a capability the model calls; a capability the model calls names no place (its
// result goes into the tool's result) and no turn (the model calls it on any turn it is offered), and it is offered
// only on a head whose dialect is Anthropic's Messages.
package splice.core.capability

import kotlinx.serialization.json.JsonObject

/** Reads `call` and `tool` of one table, each wrong one a finding. [messages] is the heads whose dialect is Anthropic's
 *  Messages; null leaves that check out (a file that did not decode has no heads to check against). */
internal class CapabilityCallCheck(private val values: CapabilityValues, private val messages: Set<String>?) {
    private val keys = values.table.keys

    /** Who may call the capability on [heads], and its tool. A wrong key's finding is made, and reads as the rule. */
    fun read(heads: List<String>): CapabilityCalling {
        val caller = values.choice(CALL, keys[CALL], CALLERS, CapabilityCaller.RULE)
        val tool = tool()
        if (caller != null) {
            tooled(caller)
            if (caller == CapabilityCaller.AGENT) unruled()
            if (caller.byAgent) spoken(caller, heads)
        }
        return CapabilityCalling(caller ?: CapabilityCaller.RULE, tool = tool)
    }

    /** A tool goes with a capability the model calls, and only with one. */
    private fun tooled(caller: CapabilityCaller) {
        when {
            caller.byAgent && TOOL !in keys ->
                values.fault(TOOL, "is required with call = ${caller.wire}: $TOOL_SHAPE")
            !caller.byAgent && TOOL in keys ->
                values.fault(TOOL, "is offered to the model only with call = agent or both")
        }
    }

    /** What only a rule's call has: a place for its text, and the turn it runs on. */
    private fun unruled() {
        if (PLACE in keys) values.fault(PLACE, "is not named with call = agent: the result goes into the tool's result")
        if ((keys[WHEN] as? JsonObject)?.containsKey(TURN) == true) {
            values.fault("$WHEN.$TURN", "is not named with call = agent: the model calls its tool on any turn")
        }
    }

    /** Each of [heads] has to speak Anthropic's Messages, where the model's call is offered. */
    private fun spoken(caller: CapabilityCaller, heads: List<String>) {
        val known = messages ?: return
        heads.filter { it !in known }.forEach {
            values.fault(
                HEADS,
                "names head \"${values.safe(it)}\", whose dialect is not Anthropic's Messages; " +
                    "call = ${caller.wire} is offered only on an anthropic-passthrough head",
            )
        }
    }

    /** The `tool` table: a name the API takes, a description and the input's schema as a table. */
    private fun tool(): CapabilityTool? {
        val tool = table() ?: return null
        tool.keys.filter { it !in TOOL_KEYS }.forEach { values.fault("$TOOL.$it", "is not a key a tool has") }
        val name = required(tool, "name", "letters, digits, _ and -, at most 64") { TOOL_NAME.matches(it) }
        val description = required(tool, "description", "what the tool does, as the model reads it") { it.isNotBlank() }
        val input = tool["input"] as? JsonObject
        if (input == null) values.fault("$TOOL.input", "is required: the JSON schema of the tool's input, as a table")
        return name?.let { named -> description?.let { said -> input?.let { CapabilityTool(named, said, it) } } }
    }

    /** The `tool` key as a table; null when it is not there, and a finding when it is something else. */
    private fun table(): JsonObject? {
        val value = keys[TOOL] ?: return null
        return value as? JsonObject ?: null.also { values.fault(TOOL, "must be a table: $TOOL_SHAPE") }
    }

    /** The text at [key] of [tool] when [fits] takes it; otherwise a finding saying [what] it must be. */
    private fun required(tool: JsonObject, key: String, what: String, fits: TextFits): String? {
        val text = values.string(tool[key])?.takeIf { fits(it) }
        if (text == null) values.fault("$TOOL.$key", "is required: $what")
        return text
    }

    /** Whether a tool's text is one its key takes. */
    private fun interface TextFits {
        operator fun invoke(text: String): Boolean
    }
}

private const val CALL = "call"
private const val TOOL = "tool"
private const val PLACE = "place"
private const val WHEN = "when"
private const val TURN = "turn"
private const val HEADS = "heads"
private const val TOOL_SHAPE = "{ name = \"...\", description = \"...\", input = { type = \"object\", ... } }"
private val TOOL_KEYS = setOf("name", "description", "input")
private val CALLERS = CapabilityCaller.entries.associateBy { it.wire }

/** why: the Messages API takes a tool name of 1 to 64 letters, digits, `_` and `-`. */
private val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")

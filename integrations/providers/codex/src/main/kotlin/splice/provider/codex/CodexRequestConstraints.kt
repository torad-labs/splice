// NEW: ChatGPT Codex request limitations, kept at the provider boundary rather than in the Responses dialect.
package splice.provider.codex

import splice.core.wire.AnthropicRequest

private const val SCHEMA_TOOL = "StructuredOutput"

/** A requested tool choice must be enforced or refused, never silently replaced with auto. */
internal object CodexRequestConstraints {
    /** Official Codex sends auto. Other choices have no established ChatGPT backend support on this path.
     *  A forced schema tool is implemented by strict schema-only output and translated back into its tool input. */
    fun refusal(body: AnthropicRequest): String? {
        val choice = body.toolChoice ?: return null
        val schemaTool = body.tools.any { it.name == SCHEMA_TOOL && it.inputSchema != null }
        if (choice.type == "auto") return null
        return if (schemaTool && schemaChoice(body)) {
            null
        } else {
            "The ChatGPT Codex backend's support for the requested tool_choice is unverified on this path; " +
                "splice cannot guarantee that choice and will not replace it with auto."
        }
    }

    private fun schemaChoice(body: AnthropicRequest): Boolean = when (body.toolChoice?.type) {
        "tool" -> body.toolChoice?.name == SCHEMA_TOOL
        "any", "required" -> body.tools.size == 1 && body.tools.first().name == SCHEMA_TOOL
        else -> false
    }
}

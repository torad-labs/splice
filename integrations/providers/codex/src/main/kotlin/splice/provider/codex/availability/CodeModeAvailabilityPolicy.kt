package splice.provider.codex.availability

import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodeModeToolPolicy
import splice.upstream.codemode.availability.CodeModeAvailabilityPlan

/** Preflight, warning ownership, and dispatch use the same source tool-mode decision. */
internal class CodeModeAvailabilityPolicy(private val config: CodeModeBridgeConfig) {
    private val owners = CodeModeAvailabilityOwners(config)

    fun plan(policy: CodeModeToolPolicy): CodeModeAvailabilityPlan =
        CodeModeAvailabilityPlan(config.runtimes.availability(), policy.presentation.toolMode)

    fun announce(context: CodeModeRunContext) {
        val message = plan(context.turn.toolPolicy).warning ?: return
        if (!owners.claim(context.turn)) return
        config.log("[code-mode] $message")
    }

    fun clear() = owners.clear()
}

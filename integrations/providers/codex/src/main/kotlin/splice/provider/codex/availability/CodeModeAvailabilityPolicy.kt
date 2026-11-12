package splice.provider.codex.availability

import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.codemode.availability.CodeModeAvailabilityPlan

/** Preflight, warning ownership, and dispatch use the same source tool-mode decision. */
internal class CodeModeAvailabilityPolicy(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
) {
    private val identity = CodeModeTurnIdentity()

    fun plan(policy: CodeModeToolPolicy): CodeModeAvailabilityPlan =
        CodeModeAvailabilityPlan(config.runtimes.availability(), policy.presentation.toolMode)

    fun announce(context: CodeModeRunContext) {
        val message = plan(context.turn.toolPolicy).warning ?: return
        val owner = identity.conversationId(context.turn)
        if (!registry.nativeCells.services.claimUnavailableWarning(context.key, owner, context.turn.sessionId)) return
        registry.timed.arm()
        config.log("[code-mode] $message")
    }
}

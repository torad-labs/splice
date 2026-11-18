// NEW: the harness (docs/specs/harness.md, decisions 2 and 12). Whether a capability's rule names a request: its `when`
// table, every key of which a request has to meet, and splice's own conditions, which hold for every rule. The model
// is offered a capability's tool by the same rule, but for its turn, which does not apply to the model's call.
package splice.harness

import splice.core.capability.Capability
import splice.core.capability.CapabilityMode
import splice.core.capability.CapabilityThread
import splice.core.capability.CapabilityTurn
import splice.head.port.HarnessRequest

internal class CapabilityRules {
    /** Whether [capability]'s rule names [request] on head [head]. [first] is whether the request is its thread's first
     *  turn, decided once for the thread. A capability only the model calls is never called by its rule. */
    fun names(capability: Capability, head: String, request: HarnessRequest, first: Boolean): Boolean =
        capability.call.byRule &&
            allows(capability, head, request) &&
            (capability.call.rule.turn == CapabilityTurn.ANY || first)

    /** Whether the model is offered [capability]'s tool on [request] (decision 12): the rule's thread, mode and models,
     *  and splice's own conditions. */
    fun offers(capability: Capability, head: String, request: HarnessRequest): Boolean =
        capability.call.byAgent && capability.call.tool != null && allows(capability, head, request)

    private fun allows(capability: Capability, head: String, request: HarnessRequest): Boolean {
        val rule = capability.call.rule
        return capability.onFor(head) &&
            spliceAllows(request) &&
            moded(rule.mode, request.client) &&
            threaded(rule.thread, request.agentId) &&
            rule.models.let { models -> models == null || request.model in models }
    }

    /** splice's own conditions: a turn of a session (one that streams and carries tools, never a side request), and
     *  never Claude Code's auto-mode review, and never a turn holding a model-server slot of its own. */
    private fun spliceAllows(request: HarnessRequest): Boolean =
        request.sessionId != null && request.turn && !request.shape.review && !request.shape.slotHeld

    /** The client name compared whole: `cli` is a session in a terminal, `sdk-cli` a print-mode run. */
    private fun moded(mode: CapabilityMode, client: String?): Boolean = when (mode) {
        CapabilityMode.INTERACTIVE -> client == INTERACTIVE_CLIENT
        CapabilityMode.PRINT -> client == PRINT_CLIENT
        CapabilityMode.ANY -> true
    }

    /** A main-thread request carries no agent header; a subagent's names its agent. */
    private fun threaded(thread: CapabilityThread, agentId: String?): Boolean = when (thread) {
        CapabilityThread.MAIN -> agentId == null
        CapabilityThread.SUBAGENT -> agentId != null
        CapabilityThread.ANY -> true
    }
}

/** why: Claude Code's user agent names `cli` for a session in a terminal (2.1.326). */
private const val INTERACTIVE_CLIENT = "cli"

/** why: and `sdk-cli` for a print-mode run (`claude -p`), on the same version. */
private const val PRINT_CLIENT = "sdk-cli"

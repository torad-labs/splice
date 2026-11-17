// NEW: the harness (docs/specs/harness.md, decision 2). Whether a capability's rule names a request: its `when` table,
// every key of which a request has to meet, and splice's own conditions, which hold for every rule.
package splice.head.harness

import splice.core.capability.Capability
import splice.core.capability.CapabilityMode
import splice.core.capability.CapabilityThread
import splice.core.capability.CapabilityTurn

internal class CapabilityRules {
    /** Whether [capability]'s rule names [request] on head [head]. [first] is whether the request is its thread's first
     *  turn, decided once for the thread. */
    fun names(capability: Capability, head: String, request: HarnessRequest, first: Boolean): Boolean {
        val rule = capability.rule
        return capability.onFor(head) &&
            spliceAllows(request) &&
            moded(rule.mode, request.client) &&
            threaded(rule.thread, request.agentId) &&
            rule.models.let { models -> models == null || request.model in models } &&
            (rule.turn == CapabilityTurn.ANY || first)
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

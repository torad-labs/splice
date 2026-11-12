package splice.control.mcp.hub

import splice.control.mcp.McpResult

/** Completion releases only the operation's captured claim, and a failed release remains retryable. */
internal class HubReplacementPins(
    private val pins: HubDrainPins,
    private val outcomes: HubHolderRetirements,
) {
    fun run(
        values: ReplacementValues,
        completed: HubRetirementState,
        operation: ReplacementAction,
    ): McpResult<Unit> {
        val claim = values.claim?.takeIf { it.isNotEmpty() }
            ?: return McpResult.Refused("the replacement names no exact front owner claim")
        val old = values.old
        val request = HubDrainPinRequest(old.server, old.launch, old.oldSocket, old.oldGeneration)
        if (!pins.hasClaim(request, claim)) {
            return McpResult.Refused("the replacement no longer owns the exact front drain claim")
        }
        return if (pins.activeClaim(request, claim)) {
            when (val result = operation(values)) {
                is McpResult.Refused -> result
                is McpResult.Served -> release(request, claim)
            }
        } else {
            replay(values, completed)
        }
    }

    private fun release(request: HubDrainPinRequest, claim: String): McpResult<Unit> =
        if (pins.release(request, claim)) {
            McpResult.Served(Unit)
        } else {
            McpResult.Refused("the completed replacement owner claim could not be released durably")
        }

    /** Released ownership can replay an exact terminal receipt, but cannot start or continue a process action. */
    private fun replay(values: ReplacementValues, completed: HubRetirementState): McpResult<Unit> {
        val replacement = HubReplacement(
            values.old.oldSocket,
            values.old.oldGeneration,
            values.candidateSocket,
            values.candidateTuple,
            values.candidateGeneration,
        )
        return if (outcomes.state(replacement) == completed) {
            McpResult.Served(Unit)
        } else {
            McpResult.Refused("the inactive owner claim has no matching completed replacement outcome")
        }
    }
}

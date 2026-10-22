package splice.control.mcp.hub

import splice.control.mcp.McpResult

/** Completion releases only the operation's captured claim, and a failed release remains retryable. */
internal class HubReplacementPins(private val pins: HubDrainPins) {
    fun run(values: ReplacementValues, operation: ReplacementAction): McpResult<Unit> {
        val claim = values.claim?.takeIf { it.isNotEmpty() }
            ?: return McpResult.Refused("the replacement names no exact front owner claim")
        val old = values.old
        val request = HubDrainPinRequest(old.server, old.launch, old.oldSocket, old.oldGeneration)
        if (!pins.hasClaim(request, claim)) {
            return McpResult.Refused("the replacement no longer owns the exact front drain claim")
        }
        return when (val result = operation(values)) {
            is McpResult.Refused -> result
            is McpResult.Served -> {
                if (pins.release(request, claim)) {
                    result
                } else {
                    McpResult.Refused("the completed replacement owner claim could not be released durably")
                }
            }
        }
    }
}

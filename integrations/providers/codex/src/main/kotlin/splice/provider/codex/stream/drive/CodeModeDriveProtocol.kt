package splice.provider.codex.stream.drive

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRunContext

/** One iteration's model input, outcome and optional admitted outer call. */
internal data class CodeModeDriveState(
    var body: CodeModeBody,
    var outcome: TurnOutcome,
    var suppliedOuter: GatewayCustomCall?,
)

/** Bounded outer-call admission and the local protocol failures it reports. */
internal class CodeModeDriveProtocol(private val config: CodeModeBridgeConfig) {
    fun problem(
        context: CodeModeRunContext,
        calls: List<GatewayCustomCall>,
        outer: GatewayCustomCall?,
    ): String? {
        val hasTooManyCalls = calls.size > 1
        val isMissingOuter = outer == null && calls.isNotEmpty()
        if (hasTooManyCalls || isMissingOuter) return "code mode accepts one outer custom call per round"
        if (outer == null) return null
        return when {
            context.completed.any { it.origin.outerCallId == outer.callId } ->
                "duplicate completed code-mode call id '${outer.callId}'"
            config.bounds.maxRounds?.let { context.scripts >= it } == true -> "code-mode round limit exceeded"
            else -> null
        }
    }

    fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(
            // V4-117: every path through this helper is the code-mode machine reporting its own
            // protocol state, so they share ONE cause rather than each borrowing an upstream one.
            // The type PARAMETER this used to take is gone with the hand-picked type itself: the
            // callers were choosing between INVALID_REQUEST and API_ERROR for the same cause, which
            // is exactly the second author the derived type removes.
            cause = FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_STATE),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence(message),
            salvagedUsage = noRequestUsage,
        )
}

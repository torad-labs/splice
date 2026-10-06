// NEW: whole-source and streamed admission use the same durable no-rerun record identity.
package splice.provider.codex.stream

import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeTurnIdentity
import java.util.UUID

internal fun interface CodeModeStreamAdmission {
    fun admit(call: GatewayCustomCall): CodeModeRecord
}

internal class CodeModeRecordFactory(private val config: CodeModeBridgeConfig, private val wire: CodexCodeModeWire) {
    fun create(
        context: CodeModeRunContext,
        outer: GatewayCustomCall,
        body: CodeModeBody,
        outcome: TurnOutcome.Success,
    ): CodeModeRecord? {
        val recovery = context.recovery
        val upstream = recovery?.upstream(context.completed) ?: context.completed
        val postedBoundary = wire.anchoredBoundary(body, upstream) ?: return null
        val boundary = if (recovery == null) {
            postedBoundary
        } else {
            recovery.clientBoundary(context.completed, wire) ?: return null
        }
        val rawContinuity = wire.continuity(outcome)
        val continuity = recovery?.continuity(outcome, wire) ?: rawContinuity
        val native = CodeModeNativeChain.capture(boundary.nativeSegments, context.completed.lastOrNull())
        return CodeModeRecord(
            id = UUID.randomUUID().toString(),
            key = context.key,
            outer = outer.raw,
            outerCallId = outer.callId,
            source = outer.input,
            phase = CodeModePhase.LOST,
            updatedAt = config.clock.millis(),
            lastDigest = context.digest,
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = boundary.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = boundary.logicalDigest,
            nativeSegments = native.segments,
            continuity = continuity.logicalItems,
            continuityReplay = continuity.replayItems,
        ).also {
            it.replayAnchors = boundary.replayAnchors
            it.sessionId = context.turn.sessionId
            it.conversationId = CodeModeTurnIdentity().conversationId(context.turn)
            it.nativeBaseId = native.parent?.id
            it.nativeParent = native.parent
            recovery?.remember(
                it,
                postedBoundary,
                CodeModeNativeChain.capture(postedBoundary.nativeSegments, upstream.lastOrNull()),
                rawContinuity,
                outcome.bodyText.takeIf { outcome.emittedText }.orEmpty(),
            )
        }
    }
}

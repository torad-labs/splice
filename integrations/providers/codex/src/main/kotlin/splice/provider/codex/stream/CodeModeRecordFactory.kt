// NEW: whole-source and streamed admission use the same durable no-rerun record identity.
package splice.provider.codex.stream

import splice.core.turn.GatewayCustomCall
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeInputBoundary
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeTurnIdentity
import java.util.UUID

internal class CodeModeRecordFactory(private val config: CodeModeBridgeConfig) {
    fun create(
        context: CodeModeRunContext,
        outer: GatewayCustomCall,
        boundary: CodeModeInputBoundary,
        continuity: CodeModeContinuity,
    ): CodeModeRecord {
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
        }
    }
}

// NEW: restored execution state is separate from immutable serialized record data.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_LEGACY_METADATA_VERSION
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeAccepted
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot

internal class CodeModeRecordRestorer {
    fun restore(saved: CodeModeRecordSnapshot): CodeModeRecord {
        val stale = stale(saved)
        val lost = lost(saved)
        val error = when {
            stale -> "code-mode replay metadata is unavailable; source was not rerun"
            lost -> {
                "code-mode process state was lost after completed client calls: " +
                    "${saved.results.keys}; source was not rerun"
            }
            else -> saved.error
        }
        return record(saved, if (stale || lost) CodeModePhase.LOST else saved.phase, error).also {
            it.issued.addAll(saved.issued)
            it.sessionId = saved.sessionId
            it.conversationId = saved.conversationId
            it.nativeBaseId = saved.nativeBaseId
            it.replayAnchors = saved.replayAnchors
            it.retainedBytes = saved.retainedBytes
            it.sourceState = saved.sourceState
        }
    }

    private fun stale(saved: CodeModeRecordSnapshot): Boolean =
        saved.phase != CodeModePhase.COMPLETED &&
            saved.metadataVersion !in CODE_MODE_LEGACY_METADATA_VERSION..CODE_MODE_METADATA_VERSION

    private fun lost(saved: CodeModeRecordSnapshot): Boolean =
        saved.phase != CodeModePhase.COMPLETED &&
            (saved.phase == CodeModePhase.ACTIVE || saved.sourceState?.complete == false)

    private fun record(saved: CodeModeRecordSnapshot, phase: CodeModePhase, error: String?) = CodeModeRecord(
        id = saved.id,
        key = saved.key,
        outer = saved.outer,
        outerCallId = saved.outerCallId,
        source = saved.source,
        phase = phase,
        pending = saved.pending.toMutableList(),
        accepted = CodeModeAccepted(saved.results.mapValues { (id, value) -> value.restore(id) }),
        output = saved.output,
        error = error,
        totalCalls = saved.totalCalls,
        rounds = saved.rounds,
        updatedAt = saved.updatedAt,
        lastDigest = saved.lastDigest,
        baselineInputCount = saved.baselineInputCount,
        baselineInputDigest = saved.baselineInputDigest,
        metadataVersion = saved.metadataVersion,
        baselineLogicalCount = saved.baselineLogicalCount,
        baselineLogicalDigest = saved.baselineLogicalDigest,
        nativeSegments = saved.nativeSegments,
        continuity = saved.continuity,
        continuityReplay = saved.continuityReplay,
    )
}

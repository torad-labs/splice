// NEW: restored execution state is separate from immutable serialized record data.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_LEGACY_METADATA_VERSION
import splice.provider.codex.CODE_MODE_METADATA_VERSION
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
            it.accepted.restore(saved.results)
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

/** Immutable copies preserve every source, replay and continuity field under the same conversation lock. */
internal object CodeModeRecordSnapshots {
    fun of(record: CodeModeRecord): CodeModeRecordSnapshot = CodeModeRecordSnapshot(
        id = record.id,
        key = record.key,
        outer = record.outer,
        outerCallId = record.outerCallId,
        source = record.source,
        phase = record.phase,
        pending = record.pending.map { it.copy() },
        results = record.accepted.snapshot(),
        output = record.output,
        error = record.error,
        totalCalls = record.totalCalls,
        rounds = record.rounds,
        updatedAt = record.updatedAt,
        lastDigest = record.lastDigest,
        baselineInputCount = record.baselineInputCount,
        baselineInputDigest = record.baselineInputDigest,
        metadataVersion = record.metadataVersion,
        baselineLogicalCount = record.baselineLogicalCount,
        baselineLogicalDigest = record.baselineLogicalDigest,
        nativeSegments = record.nativeSegments,
        continuity = record.continuity,
        continuityReplay = record.continuityReplay,
    ).also {
        it.issued = record.issued.toList()
        it.sessionId = record.sessionId
        it.conversationId = record.conversationId
        it.nativeBaseId = record.nativeBaseId
        it.replayAnchors = record.replayAnchors
        it.sourceState = record.sourceState
    }
}

// NEW: restored execution state is separate from immutable serialized record data.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_LEGACY_METADATA_VERSION
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeBaseline
import splice.provider.codex.CodeModeNativeContinuity
import splice.provider.codex.CodeModeOrigin
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeProgress
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
        return record(saved, if (stale || lost) CodeModePhase.LOST else saved.phase, error).also { record ->
            record.accepted.restore(saved.results)
            record.issued.addAll(saved.issued)
            record.sessionId = saved.sessionId
            record.conversationId = saved.conversationId
            record.nativeBaseId = saved.nativeBaseId
            record.replayAnchors = saved.replayAnchors
            record.retainedBytes = saved.retainedBytes
            record.sourceState = saved.sourceState
            CodeModeHeap.adopt(record, saved)
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
        phase = phase,
        error = error,
        origin = CodeModeOrigin(
            outer = saved.outer,
            outerCallId = saved.outerCallId,
            source = saved.source,
            baseline = CodeModeBaseline(
                inputCount = saved.baselineInputCount,
                inputDigest = saved.baselineInputDigest,
                logicalCount = saved.baselineLogicalCount,
                logicalDigest = saved.baselineLogicalDigest,
                metadataVersion = saved.metadataVersion,
            ),
        ),
        progress = CodeModeProgress(
            pending = saved.pending.toMutableList(),
            output = saved.output,
            totalCalls = saved.totalCalls,
            rounds = saved.rounds,
            updatedAt = saved.updatedAt,
            lastDigest = saved.lastDigest,
        ),
        carry = CodeModeNativeContinuity(saved.nativeSegments, saved.continuity, saved.continuityReplay),
    )
}

/** Immutable copies preserve every source, replay and continuity field under the same conversation lock. */
internal object CodeModeRecordSnapshots {
    fun of(record: CodeModeRecord): CodeModeRecordSnapshot = CodeModeRecordSnapshot(
        id = record.id,
        key = record.key,
        outer = record.origin.outer,
        outerCallId = record.origin.outerCallId,
        source = record.origin.source,
        phase = record.phase,
        pending = record.progress.pending.map { it.copy() },
        results = record.accepted.snapshot(),
        output = record.progress.output,
        error = record.error,
        totalCalls = record.progress.totalCalls,
        rounds = record.progress.rounds,
        updatedAt = record.progress.updatedAt,
        lastDigest = record.progress.lastDigest,
        baselineInputCount = record.origin.baseline.inputCount,
        baselineInputDigest = record.origin.baseline.inputDigest,
        metadataVersion = record.origin.baseline.metadataVersion,
        baselineLogicalCount = record.origin.baseline.logicalCount,
        baselineLogicalDigest = record.origin.baseline.logicalDigest,
        nativeSegments = record.carry.segments,
        continuity = record.carry.continuity,
        continuityReplay = record.carry.replay,
    ).also {
        it.issued = record.issued.toList()
        it.sessionId = record.sessionId
        it.conversationId = record.conversationId
        it.nativeBaseId = record.nativeBaseId
        it.replayAnchors = record.replayAnchors
        it.sourceState = record.sourceState
    }
}

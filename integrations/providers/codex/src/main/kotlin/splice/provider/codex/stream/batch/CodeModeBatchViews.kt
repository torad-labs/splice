package splice.provider.codex.stream.batch

import kotlinx.serialization.json.JsonArray
import splice.provider.codex.CodeModeNativeContinuity
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

/** A transient aggregate lets existing callback folding own a whole serial response without duplicating its items. */
internal class CodeModeBatchViews(private val codec: CodexCodeModeHistoryCodec) {
    fun of(records: List<CodeModeRecord>): List<CodeModeRecord> {
        CodeModeBatchLinks.link(records)
        return records.mapNotNull { record ->
            when {
                !CodeModeBatchLinks.multiple(record) -> record
                CodeModeBatchHistory.owner(record).id != record.id -> null
                CodeModeBatchHistory.finished(record) != true -> null
                else -> view(record)
            }
        }
    }

    private fun view(owner: CodeModeRecord): CodeModeRecord {
        val members = owner.nativeBatchMembers
        val packet = checkNotNull(CodeModeBatchHistory.continuity(owner)) +
            checkNotNull(CodeModeBatchHistory.outputs(owner, codec))
        val projected = codec.projection.project(JsonArray(packet))
        val carry = CodeModeNativeContinuity(
            owner.carry.segments,
            projected.logicalItems,
            projected.replayItems.map { CodeModeNativeSegment(it.logicalOffset, it.items) },
        )
        val view = CodeModeRecord(
            owner.id,
            owner.key,
            owner.phase,
            owner.error,
            owner.origin,
            owner.progress.copy(pending = members.flatMap { it.progress.pending }.toMutableList()),
            carry,
        )
        view.accepted.restore(members.flatMap { it.accepted.snapshot().entries }.associate { it.toPair() })
        view.issued.addAll(members.flatMap { it.issued })
        view.progress.nativeOutput = owner.progress.nativeOutput
        view.progress.nativeNotifications = members.flatMap { it.progress.nativeNotifications }
        view.replayAnchors = owner.replayAnchors
        view.nativeParent = owner.nativeParent
        view.nativeBaseId = owner.nativeBaseId
        view.nativeBatch = owner.nativeBatch
        view.nativeBatchOwner = owner
        view.nativeBatchMembers = members
        return view
    }
}

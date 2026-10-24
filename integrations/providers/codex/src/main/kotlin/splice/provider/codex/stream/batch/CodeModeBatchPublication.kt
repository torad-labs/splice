package splice.provider.codex.stream.batch

import splice.core.turn.GatewayCustomCall
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeHeap
import splice.provider.codex.state.CodeModeRecordChange

/** The durable append contains the new source, both membership links and the exact prepared model prefix together. */
internal class CodeModeBatchPublication(private val registry: CodexCodeModeRegistry) {
    fun publish(
        records: List<CodeModeRecord>,
        record: CodeModeRecord,
        call: GatewayCustomCall,
        continuity: CodeModeContinuity?,
        items: List<kotlinx.serialization.json.JsonObject>,
    ): Boolean {
        val owner = records.first()
        val ownerGrowth = (
            CodeModeBatchWeight.bytes(
                CodeModeNativeBatch(owner.id, 0, size = records.size, items = items),
            ) - CodeModeBatchWeight.bytes(owner.nativeBatch)
            ).coerceAtLeast(0L)
        var before: CodeModeBatchUndo? = null
        registry.changes.save(
            owner,
            undo = CodeModeRecordChange { before?.restore() },
            growthBytes = ownerGrowth,
            wholeKey = true,
        ) {
            val nextOwner = (owner.nativeBatch ?: CodeModeNativeBatch(owner.id, 0)).copy(
                dispatched = dispatched(owner),
                size = records.size,
                settled = false,
                items = items,
            )
            val nextMember = CodeModeNativeBatch(owner.id, records.lastIndex, dispatched = dispatched(record))
            if (owner !== record) CodeModeHeap.grow(record, growth(nextMember, record.nativeBatch))
            before = CodeModeBatchUndo(owner, record)
            check(registry.source.seal(record, call, continuity, persist = false)) { "source admission is unavailable" }
            owner.nativeBatch = nextOwner
            if (!nextOwner.dispatched) owner.phase = CodeModePhase.STARTING
            if (owner !== record) {
                record.nativeBatch = nextMember
                if (!nextMember.dispatched) record.phase = CodeModePhase.STARTING
            }
        }
        return true
    }

    private fun growth(next: CodeModeNativeBatch, prior: CodeModeNativeBatch?): Long =
        (CodeModeBatchWeight.bytes(next) - CodeModeBatchWeight.bytes(prior)).coerceAtLeast(0L)

    private fun dispatched(record: CodeModeRecord): Boolean =
        record.dispatchStarted || record.nativeBatch?.dispatched == true
}

/** An unsuccessful append cannot publish live membership or an uncommitted executable seal. */
private class CodeModeBatchUndo(private val owner: CodeModeRecord, private val record: CodeModeRecord) {
    private val ownerBefore = owner.snapshot()
    private val member = if (owner === record) ownerBefore else record.snapshot()
    fun restore() {
        owner.nativeBatch = ownerBefore.nativeBatch
        owner.phase = ownerBefore.phase
        record.nativeBatch = member.nativeBatch
        record.phase = member.phase
        record.sourceState = member.sourceState
        record.origin.outer = member.outer
        record.origin.source = member.source
        record.carry.continuity = member.continuity
        record.carry.replay = member.continuityReplay
        record.carry.nativeAfterExec = member.nativeAfterExec
    }
}

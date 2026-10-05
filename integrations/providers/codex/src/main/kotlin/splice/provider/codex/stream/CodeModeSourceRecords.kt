// NEW: source staging and billing commits share the callback and result transition conversation key.
package splice.provider.codex.stream

import splice.core.turn.GatewayCustomCall
import splice.core.turn.Usage
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeHeap
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeWeight.STORED

internal class CodeModeSourceRecords(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
    private val contexts: CodeModeClientContexts,
) {
    /** The durable no-rerun admission precedes execution; prefixes join the next client-visible batch. */
    fun append(record: CodeModeRecord, text: String): Boolean = access.withKey(record.key) {
        if (record !in records || record.error != null) return@withKey false
        // Completion may already have staged a longer prefix while this cursor was waking.
        if (record.source.startsWith(text)) return@withKey true
        check(text.startsWith(record.source)) { "dispatched source changed" }
        // One appended character outside Latin-1 re-widens the whole source, so the growth is the stored difference.
        val replaced = record.source
        CodeModeHeap.grow(record, (STORED.text(text) - STORED.text(replaced)).coerceAtLeast(0L)) { kept ->
            if (kept.source === replaced) STORED.text(replaced) else 0L
        }
        record.source = text
        true
    }

    /** Item completion alone is not response completion. Only the terminal round finalizes these values. */
    fun finish(record: CodeModeRecord, call: GatewayCustomCall, continuity: CodeModeContinuity, usage: Usage): Boolean =
        access.withKey(record.key) {
            if (record !in records || record.error != null) return@withKey false
            val next = STORED.json(call.raw) + STORED.text(call.input) +
                continuity.logicalItems.sumOf(STORED::json) + continuity.replayItems.sumOf(STORED::segment)
            val before = STORED.json(record.outer) + STORED.text(record.source) +
                record.continuity.sumOf(STORED::json) + record.continuityReplay.sumOf(STORED::segment)
            CodeModeHeap.grow(record, (next - before).coerceAtLeast(0L)) { kept -> replaced(kept, record) }
            record.outer = call.raw
            record.source = call.input
            record.continuity = continuity.logicalItems
            record.continuityReplay = continuity.replayItems
            record.sourceState = CodeModeSourceState(
                complete = true,
                usage = CodeModeSourceUsage(
                    usage.inputTokens,
                    usage.outputTokens,
                    usage.cachedTokens,
                    usage.reasoningTokens,
                    usage.cacheWriteTokens,
                    usage.recordedOutputTokens,
                ),
            )
            // A round that finishes after its client turn ended is still the conversation's newest context.
            contexts.note(record.key, usage)
            true
        }

    /** A result request takes terminal billing once, and the claim survives a later restart. */
    fun consume(record: CodeModeRecord): Usage? = access.withKey(record.key) {
        val state = record.sourceState ?: return@withKey null
        if (state.consumed) return@withKey null
        val usage = state.usage ?: return@withKey null
        record.sourceState = state.copy(consumed = true)
        if (record !in records) return@withKey usage.value()
        val generation = record.saveGeneration + 1
        try {
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
        } catch (error: CodeModePersistenceException) {
            if (record.saveGeneration <= generation) record.sourceState = state
            throw error
        }
        usage.value()
    }

    /** What [kept] still holds of the payloads a finished round replaces on [record]. */
    private fun replaced(kept: CodeModeRecordSnapshot, record: CodeModeRecord): Long {
        val outer = if (kept.outer === record.outer) STORED.json(record.outer) else 0L
        val source = if (kept.source === record.source) STORED.text(record.source) else 0L
        val logical = if (kept.continuity === record.continuity) record.continuity.sumOf(STORED::json) else 0L
        val replay = record.continuityReplay.takeIf { it === kept.continuityReplay }?.sumOf(STORED::segment) ?: 0L
        return outer + source + logical + replay
    }
}

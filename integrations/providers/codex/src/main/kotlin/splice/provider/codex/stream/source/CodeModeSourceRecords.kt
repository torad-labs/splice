// NEW: source staging and billing commits share the callback and result transition conversation key.
package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonObject
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
import splice.provider.codex.stream.CodeModeClientContexts
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeSourceUsage

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
        if (record.origin.source.startsWith(text)) return@withKey true
        check(text.startsWith(record.origin.source)) { "dispatched source changed" }
        // One appended character outside Latin-1 re-widens the whole source, so the growth is the stored difference.
        val replaced = record.origin.source
        CodeModeHeap.grow(record, (STORED.text(text) - STORED.text(replaced)).coerceAtLeast(0L)) { kept ->
            if (kept.source === replaced) STORED.text(replaced) else 0L
        }
        record.origin.source = text
        true
    }

    /** Persist the exact completed item before any worker can execute it, under the admission's no-rerun identity. */
    fun seal(record: CodeModeRecord, call: GatewayCustomCall, continuity: CodeModeContinuity? = null): Boolean =
        access.withKey(record.key) {
            if (record !in records || record.error != null) return@withKey false
            stage(record, call, continuity ?: current(record))
            record.sourceState = (record.sourceState ?: CodeModeSourceState()).copy(itemComplete = true)
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            true
        }

    /** Completed native items and confirmed prose at a callback cutoff survive independently of response billing. */
    fun updateContinuity(record: CodeModeRecord, call: GatewayCustomCall, continuity: CodeModeContinuity): Boolean =
        access.withKey(record.key) {
            if (record !in records || record.error != null) return@withKey false
            if (current(record) == continuity) return@withKey true
            stage(record, call, continuity)
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            true
        }

    private fun current(record: CodeModeRecord): CodeModeContinuity = CodeModeContinuity(
        record.carry.continuity,
        record.carry.replay,
        record.carry.nativeAfterExec,
    )

    /** A live reader's raw receipt remains owned after its stream lease ends, until delivery can certify its tail. */
    fun holdPayload(record: CodeModeRecord, items: Map<JsonObject, Long>): Boolean = access.withKey(record.key) {
        if (record !in records || record.error != null) return@withKey false
        CodeModeHeap.grow(record, record.sourceClaims.additional(items, record.carry.nativeAfterExec))
        record.sourceClaims.retain(items, record.carry.nativeAfterExec)
        true
    }

    /** Response accounting can arrive after the cell completed; keep its sealed identity and issuance history. */
    fun finish(record: CodeModeRecord, call: GatewayCustomCall, continuity: CodeModeContinuity, usage: Usage): Boolean =
        access.withKey(record.key) {
            if (record !in records || record.error != null) return@withKey false
            stage(record, call, continuity)
            record.sourceState = CodeModeSourceState(
                complete = true,
                usage = sourceUsage(usage),
            )
            // A round that finishes after its client turn ended is still the conversation's newest context.
            contexts.note(record.key, usage)
            true
        }

    /** A late reader failure ends accounting without revoking the completed item or its live cell. */
    fun interrupted(record: CodeModeRecord, usage: Usage? = null) = access.withKey(record.key) {
        if (record !in records || record.error != null) return@withKey
        val state = record.sourceState?.takeIf { it.itemComplete } ?: return@withKey
        record.sourceState = state.copy(complete = true, usage = usage?.let(::sourceUsage) ?: state.usage)
        if (usage != null) contexts.note(record.key, usage)
        store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
    }

    private fun stage(record: CodeModeRecord, call: GatewayCustomCall, continuity: CodeModeContinuity) {
        val next = STORED.json(call.raw) + STORED.text(call.input) +
            continuity.logicalItems.sumOf(STORED::json) + continuity.replayItems.sumOf(STORED::segment) +
            continuity.nativeAfterExec.orEmpty().sumOf(STORED::json)
        val before = STORED.json(record.origin.outer) + STORED.text(record.origin.source) +
            record.carry.continuity.sumOf(STORED::json) + record.carry.replay.sumOf(STORED::segment) +
            record.carry.nativeAfterExec.orEmpty().sumOf(STORED::json)
        val credit = record.sourceClaims.credit(continuity.nativeAfterExec, record.carry.nativeAfterExec)
        CodeModeHeap.grow(record, (next - before - credit).coerceAtLeast(0L)) { kept ->
            replaced(kept, record, continuity, call)
        }
        record.origin.outer = call.raw
        record.origin.source = call.input
        record.carry.continuity = continuity.logicalItems
        record.carry.replay = continuity.replayItems
        record.carry.nativeAfterExec = continuity.nativeAfterExec
        record.sourceClaims.transferred(continuity.nativeAfterExec)
    }

    private fun sourceUsage(usage: Usage): CodeModeSourceUsage = CodeModeSourceUsage(
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        cachedTokens = usage.cachedTokens,
        reasoningTokens = usage.reasoningTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
        recordedOutputTokens = usage.origin.recordedOutputTokens,
        reported = usage.reported,
        cacheWriteHourlyTokens = usage.cacheWriteHourlyTokens,
    )

    /** Saves a finished source no billing claim saved, so an exact retry after a hard stop still finds it complete.
     *  Its usage stays unconsumed for its eventual owner; a removed or lost record is never written back. */
    fun checkpoint(record: CodeModeRecord) = access.withKey(record.key) {
        val finished = record.error == null && record.sourceState?.complete == true
        if (record !in records || !finished) return@withKey
        store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
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
    private fun replaced(
        kept: CodeModeRecordSnapshot,
        record: CodeModeRecord,
        next: CodeModeContinuity,
        call: GatewayCustomCall,
    ): Long {
        val logical =
            if (next.logicalItems !== kept.continuity && kept.continuity === record.carry.continuity) {
                record.carry.continuity.sumOf(STORED::json)
            } else {
                0L
            }
        val replay = record.carry.replay.takeIf { it === kept.continuityReplay && it !== next.replayItems }
            ?.sumOf(STORED::segment) ?: 0L
        val after = record.carry.nativeAfterExec.takeIf { it === kept.nativeAfterExec }
            ?.filter { old -> next.nativeAfterExec.orEmpty().none { it === old } }?.sumOf(STORED::json) ?: 0L
        return replacedOrigin(kept, record, call) + logical + replay + after
    }

    private fun replacedOrigin(kept: CodeModeRecordSnapshot, record: CodeModeRecord, call: GatewayCustomCall): Long {
        val outer = if (call.raw !== kept.outer && kept.outer === record.origin.outer) STORED.json(kept.outer) else 0L
        val source = if (call.input !== kept.source && kept.source === record.origin.source) {
            STORED.text(kept.source)
        } else {
            0L
        }
        return outer + source
    }
}

// NEW: source staging and billing commits share the callback and result transition conversation key.
package splice.provider.codex.stream

import splice.core.turn.GatewayCustomCall
import splice.core.turn.Usage
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeRegistryAccess

internal class CodeModeSourceRecords(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
) {
    /** The durable no-rerun admission precedes execution; prefixes join the next client-visible batch. */
    fun append(record: CodeModeRecord, text: String) = access.withKey(record.key) {
        check(record in records && record.error == null) { "code-mode source no longer owns its record" }
        // Completion may already have staged a longer prefix while this cursor was waking.
        if (record.source.startsWith(text)) return@withKey
        check(text.startsWith(record.source)) { "dispatched source changed" }
        record.source = text
    }

    /** Item completion alone is not response completion. Only the terminal round finalizes these values. */
    fun finish(record: CodeModeRecord, call: GatewayCustomCall, continuity: CodeModeContinuity, usage: Usage) =
        access.withKey(record.key) {
            check(record in records && record.error == null) { "code-mode source no longer owns its record" }
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
        }

    /** A result request takes terminal billing once, and the claim survives a later restart. */
    fun consume(record: CodeModeRecord): Usage? = access.withKey(record.key) {
        val state = record.sourceState ?: return@withKey null
        if (state.consumed) return@withKey null
        val usage = state.usage ?: return@withKey null
        record.sourceState = state.copy(consumed = true)
        val generation = record.saveGeneration + 1
        try {
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
        } catch (error: CodeModePersistenceException) {
            if (record.saveGeneration == generation) record.sourceState = state
            throw error
        }
        usage.value()
    }
}

package splice.provider.codex.stream.batch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord

/** Completion arrival, not an earlier Started index, determines eligibility for the native serial gate. */
internal class CodeModeCompletedSources {
    private val state = MutableStateFlow(CodeModeSealedItems())
    fun add(record: CodeModeRecord) { state.value = state.value.copy(items = state.value.items + record) }
    fun end() { state.value = state.value.copy(ended = true) }
    val owner: CodeModeRecord? get() = state.value.items.firstOrNull()
    suspend fun select(record: CodeModeRecord): CodeModeRecord? {
        if (dispatched(record)) return record
        val ready = state.first { value -> value.ended || value.items.any { !dispatched(it) } }
        return ready.items.firstOrNull { !dispatched(it) }
    }
    suspend fun next(record: CodeModeRecord): CodeModeRecord? {
        val ready = state.first { value -> value.ended || value.items.any { available(it, record) } }
        return ready.items.firstOrNull { available(it, record) }
    }
    private fun available(
        item: CodeModeRecord,
        current: CodeModeRecord,
    ): Boolean = item.id != current.id && !dispatched(item)
    private fun dispatched(
        record: CodeModeRecord,
    ): Boolean = record.dispatchStarted || record.nativeBatch?.dispatched == true ||
        record.runtimeDispatching || record.nativeRuntime || record.phase == CodeModePhase.ACTIVE ||
        record.phase == CodeModePhase.COMPLETED
}

private data class CodeModeSealedItems(val items: List<CodeModeRecord> = emptyList(), val ended: Boolean = false)

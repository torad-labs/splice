// NEW: bounds expiry evidence independently of the durable record schema.
package splice.provider.codex.state

import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecord
import splice.upstream.memory.JvmHeap

internal class CodeModeExpiredHistory(
    val entries: MutableList<CodeModeExpiredSnapshot>,
    private val limit: Int?,
    private val heap: HeapReservations = JvmHeap.budget,
) {
    fun remember(record: CodeModeRecord, now: Long) {
        entries += CodeModeExpiredSnapshot(
            key = record.key,
            lastDigest = record.progress.lastDigest,
            resultIds = record.clientIds(),
            expiredAt = now,
        ).also { CodeModeHeap.own(it, heap) }
        while (limit?.let { entries.size > it } == true) entries.removeAt(0)
    }
}

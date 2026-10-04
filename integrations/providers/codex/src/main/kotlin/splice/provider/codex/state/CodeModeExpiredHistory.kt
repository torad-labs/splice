// NEW: bounds expiry evidence independently of the durable record schema.
package splice.provider.codex.state

import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecord

internal class CodeModeExpiredHistory(
    val entries: MutableList<CodeModeExpiredSnapshot>,
    private val limit: Int?,
) {
    fun remember(record: CodeModeRecord, now: Long) {
        entries += CodeModeExpiredSnapshot(
            key = record.key,
            lastDigest = record.lastDigest,
            resultIds = record.clientIds(),
            expiredAt = now,
        )
        while (limit?.let { entries.size > it } == true) entries.removeAt(0)
    }
}

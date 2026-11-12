// NEW: one key's snapshots, durable force and published sizes share its reserved save lifetime.
package splice.provider.codex.state.save

import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeColdRecords
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.collection.CodeModeRecordKeys
import java.util.concurrent.locks.ReentrantLock

/** Forces a prepared conversation outside the registry-wide lock, while its key stays owned. */
internal fun interface CodeModePreparedWrite {
    operator fun invoke(item: CodeModeSaveSnapshots.Prepared)
}

internal class CodeModeSavePreparation(
    private val snapshots: CodeModeSaveSnapshots,
    private val keyLocks: CodeModeKeyLocks,
    private val registryLock: ReentrantLock?,
    private val cold: CodeModeColdRecords,
    private val uncertainKeys: MutableSet<String>,
    private val failedKeys: MutableSet<String>,
    private val write: CodeModePreparedWrite,
) {
    fun failed(key: String, uncertain: Boolean) {
        if (uncertain) uncertainKeys.add(key)
        failedKeys.add(key)
    }

    fun saveKey(
        key: String,
        records: List<CodeModeRecord>,
        expired: List<CodeModeExpiredSnapshot>,
        changedRecord: CodeModeRecord?,
    ) {
        val entry = keyLocks.acquire(key)
        try {
            // Another key can leave RAM while this save waits on an earlier key. Its remaining
            // expiry markers must never replace the full journal merely because its payload is cold.
            if (key in cold) {
                failedKeys.remove(key)
                return
            }
            registryLock?.lock()
            val prepared = try {
                snapshots.prepare(key, records, expired, changedRecord)
            } finally {
                registryLock?.unlock()
            }
            prepared.use { item ->
                item?.let(write::invoke)
                registryLock?.lock()
                try {
                    publishSizes(key, records, changedRecord, item)
                } finally {
                    registryLock?.unlock()
                }
            }
            uncertainKeys.remove(key)
            failedKeys.remove(key)
        } finally {
            keyLocks.release(key, entry)
        }
    }

    private fun publishSizes(
        key: String,
        records: List<CodeModeRecord>,
        changedRecord: CodeModeRecord?,
        prepared: CodeModeSaveSnapshots.Prepared?,
    ) {
        prepared?.nativeRoots.orEmpty().forEach { CodeModeNativeChain.publishRoot(it.live, it.snapshot) }
        val changed = prepared?.cells.orEmpty()
        if (changed.isNotEmpty()) {
            changed.forEach { it.live.retainedBytes = it.snapshot.retainedBytes }
            return
        }
        val sizes = cold.committed(key)?.records.orEmpty()
        if (changedRecord != null) {
            changedRecord.retainedBytes = sizes[changedRecord.id]?.retainedBytes
        } else {
            CodeModeRecordKeys.of(records, key).forEach { record ->
                record.retainedBytes = sizes[record.id]?.retainedBytes
            }
        }
    }
}

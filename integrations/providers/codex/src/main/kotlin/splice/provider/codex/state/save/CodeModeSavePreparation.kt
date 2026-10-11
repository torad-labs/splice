// NEW: one key's snapshots, durable force and published sizes share its reserved save lifetime.
package splice.provider.codex.state.save

import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeKeptState
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeNativeChain
import java.util.concurrent.locks.ReentrantLock

/** Forces a prepared conversation outside the registry-wide lock, while its key stays owned. */
internal fun interface CodeModePreparedWrite {
    operator fun invoke(item: CodeModeSaveSnapshots.Prepared)
}

internal class CodeModeSavePreparation(
    private val snapshots: CodeModeSaveSnapshots,
    private val keyLocks: CodeModeKeyLocks,
    private val registryLock: ReentrantLock?,
    private val kept: Map<String, CodeModeKeptState>,
    private val uncertainKeys: MutableSet<String>,
    private val failedKeys: MutableSet<String>,
    private val write: CodeModePreparedWrite,
) {
    fun saveKey(
        key: String,
        records: List<CodeModeRecord>,
        expired: List<CodeModeExpiredSnapshot>,
        changedRecord: CodeModeRecord?,
    ) {
        val entry = keyLocks.acquire(key)
        try {
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
        val sizes = kept[key]?.records.orEmpty()
        if (changedRecord != null) {
            changedRecord.retainedBytes = sizes[changedRecord.id]?.retainedBytes
        } else {
            records.filter { it.key == key }.forEach { record ->
                record.retainedBytes = sizes[record.id]?.retainedBytes
            }
        }
    }
}

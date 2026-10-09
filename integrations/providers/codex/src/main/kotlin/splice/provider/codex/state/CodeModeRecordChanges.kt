// NEW: the script driver's changes to a live code-mode record are made under the record's conversation key.
package splice.provider.codex.state

import kotlinx.coroutines.CancellationException
import splice.core.memory.HeapCapacityException
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeOmission
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.upstream.codemode.CodeModeCell
import kotlin.concurrent.withLock

/** A change to a live record, run under its conversation's key. */
internal fun interface CodeModeRecordChange {
    operator fun invoke(record: CodeModeRecord)
}

/**
 * Every change the script's driver makes to a live record, made under the record's conversation key. The
 * source reader stages prefixes under that key (CodeModeSourceRecords), and a client-visible boundary
 * snapshots the whole batch while holding it. The driver once added tool calls outside that key, so a
 * snapshot's iteration met the add and ConcurrentModificationException killed the source reader. Source,
 * result and driver mutations now share the key; only the visible boundary performs the durable save.
 */
internal class CodeModeRecordChanges(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
    private val cells: MutableMap<String, CodeModeCell>,
    private val startup: CodeModeStartupAdmissions,
    private val config: CodeModeBridgeConfig,
) {
    private val cleanup = CodeModeCellCleanup(config.log)

    fun complete(record: CodeModeRecord, output: String) = access.withKey(record.key) {
        val stored = CodeModeWeight.STORED
        val replaced = record.progress.output
        CodeModeHeap.grow(record, (stored.text(output) - stored.text(replaced.orEmpty())).coerceAtLeast(0L)) { kept ->
            if (replaced != null && kept.output === replaced) stored.text(replaced) else 0L
        }
        startup.entries.remove(record.id)
        record.progress.output = output
        record.phase = CodeModePhase.COMPLETED
        record.progress.updatedAt = config.clock.millis()
        cells.remove(record.id)?.close()
        store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
    }

    fun lose(record: CodeModeRecord, message: String, cancellation: CancellationException?) =
        access.withKey(record.key) {
            startup.entries.remove(record.id)
            record.phase = CodeModePhase.LOST
            CodeModeRecordErrors.replace(record, message)
            record.progress.updatedAt = config.clock.millis()
            cleanup.rejected(cells.remove(record.id))
            try {
                store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            } catch (error: CodeModePersistenceException) {
                if (cancellation == null) throw error
                cancellation.addSuppressed(error)
            }
        }

    /** Only witnessed edits retire completed input. Missing history and partial ancestry remain retryable views. */
    fun retireNative(omissions: List<CodeModeOmission>) {
        omissions.filter(::permanentNative).groupBy { it.record.key }.forEach { (key, rejected) ->
            access.withKey(key) {
                val ids = rejected.map { it.record.id }.toSet()
                val retired = records.filter { it.key == key && it.phase == CodeModePhase.COMPLETED }
                    .filter(::sourceSettled).filterNot(CodeModeNativeChain::retired).filter { record ->
                        generateSequence(record) { it.nativeParent }.takeWhile { it.key == key }.any { it.id in ids }
                    }
                if (retired.isEmpty()) return@withKey
                val stored = CodeModeWeight.STORED
                retired.forEach { record ->
                    CodeModeHeap.grow(
                        record,
                        (stored.text(CODE_MODE_NATIVE_RETIRED) - stored.text(record.error.orEmpty())).coerceAtLeast(0L),
                    )
                }
                retired.forEach { record ->
                    startup.entries.remove(record.id)
                    record.error = CODE_MODE_NATIVE_RETIRED
                    record.carry.segments = emptyList()
                    record.nativeParent = null
                    record.nativeBaseId = null
                    record.replayAnchors = record.replayAnchors?.copy(native = emptyMap(), nativeFollowing = emptyMap())
                }
                // Remaining active descendants retain an unknown-parent witness, never the retired payload.
                records.filter { it.key == key && it.nativeParent?.let(CodeModeNativeChain::retired) == true }
                    .forEach { it.nativeParent = null }
                store.save(records, history.entries, dirtyKeys = setOf(key))
            }
        }
    }

    private fun sourceSettled(record: CodeModeRecord): Boolean {
        val state = record.sourceState ?: return true
        return state.complete && (state.usage == null || state.consumed)
    }

    private fun permanentNative(omission: CodeModeOmission): Boolean {
        val rejected = omission.nativeRejection ?: return false
        return when (rejected.branch) {
            CodeModeNativeBranch.PAYLOAD, CodeModeNativeBranch.NATIVE_ORDER -> true
            CodeModeNativeBranch.UNEXPECTED ->
                rejected.following == true && rejected.evidence?.witness?.resolved == true
            CodeModeNativeBranch.ABSENT, CodeModeNativeBranch.COUNT -> false
        }
    }

    /** A stop is not a use. Every live cell closes without changing its record's last-use timestamp. */
    fun onHeadStop() {
        val keys = access.monitor.withLock {
            startup.stop()
            records.map(CodeModeRecord::key).distinct()
        }
        keys.forEach { key ->
            access.withKey(key) {
                records.filter { it.key == key && !it.terminal() }
                    .filter { it.phase != CodeModePhase.STARTING }.forEach { record ->
                        cells.remove(record.id)?.close()
                        record.phase = CodeModePhase.LOST
                        CodeModeRecordErrors.replace(
                            record,
                            "completed client call ids=${record.results.keys}; source was not rerun",
                        )
                    }
                store.save(records, history.entries, dirtyKeys = setOf(key))
            }
        }
    }

    /** A retry's ownership read shares the driver's key, without sweeping or ending any source. */
    fun clientIds(record: CodeModeRecord): Set<String> = access.withKey(record.key) { record.clientIds() }

    /** A change the record's next save carries. */
    fun edit(
        record: CodeModeRecord,
        growthBytes: Long = 0L,
        change: CodeModeRecordChange,
    ) = access.withKey(record.key) {
        CodeModeHeap.grow(record, growthBytes)
        change(record)
    }

    /** A change saved with its conversation under the same hold; [undo] runs when the save fails. */
    fun save(
        record: CodeModeRecord,
        undo: CodeModeRecordChange = CodeModeRecordChange {},
        growthBytes: Long = 0L,
        change: CodeModeRecordChange,
    ) = access.withKey(record.key) {
        CodeModeHeap.grow(record, growthBytes)
        change(record)
        try {
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
        } catch (error: CodeModePersistenceException) {
            undo(record)
            throw error
        }
    }
}

/** Loss must finish even at capacity. The empty error is already covered by the record's charge. */
internal object CodeModeRecordErrors {
    fun replace(record: CodeModeRecord, message: String) {
        val stored = CodeModeWeight.STORED
        val replaced = record.error
        val growth = (stored.text(message) - stored.text(replaced.orEmpty())).coerceAtLeast(0L)
        record.error = try {
            CodeModeHeap.grow(record, growth) { kept ->
                if (replaced === message) {
                    0L
                } else if (replaced != null && kept.error === replaced) {
                    stored.text(replaced)
                } else {
                    0L
                }
            }
            message
        } catch (_: HeapCapacityException) {
            ""
        }
    }
}

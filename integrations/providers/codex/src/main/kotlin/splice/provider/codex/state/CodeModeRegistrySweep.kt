package splice.provider.codex.state

import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordRetention
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.CodexCodeModeSweeper
import splice.provider.codex.stream.CodeModeNativeSessions
import kotlin.concurrent.withLock

internal class CodeModeRegistrySweep(
    private val config: CodeModeBridgeConfig,
    private val nativeSessions: CodeModeNativeSessions,
    private val access: CodeModeRegistryAccess,
    state: CodeModeSweepRecords,
    private val store: CodexCodeModeStore,
    private val sweeper: CodexCodeModeSweeper,
    private val retention: CodeModeRecordRetention,
) : Runnable {
    private val records = state.records
    private val history = state.history
    override fun run() {
        nativeSessions.ended().forEach { key ->
            access.tryKey(key) { nativeSessions.closeSession(key) }
        }
        val keys = access.monitor.withLock {
            records.map(CodeModeRecord::key).toSet() +
                history.entries.map(CodeModeExpiredSnapshot::key) + store.pendingKeys
        }
        keys.forEach { key ->
            access.tryKey(key) {
                val changed = sweeper.sweep(key)
                store.save(records, history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
            }
        }
        withinBounds()
    }

    /** The head's bounds hold on the timer too: finished conversations past them go within one sweep of
     *  losing their protection, under the same protections an admission keeps. A key whose lock is
     *  taken is passed over this sweep. */
    private fun withinBounds() {
        val passed = mutableSetOf<String>()
        while (true) {
            val key = access.monitor.withLock { retention.overBound(records, sweeper.busyKeys() + passed) } ?: return
            var went = false
            access.tryKey(key) {
                went = retention.letGoOver(records, history, key, sweeper.busyKeys(), config.clock.millis())
                if (went) store.save(records, history.entries, dirtyKeys = setOf(key))
            }
            if (!went) passed += key
        }
    }
}

internal data class CodeModeSweepRecords(val records: MutableList<CodeModeRecord>, val history: CodeModeExpiredHistory)

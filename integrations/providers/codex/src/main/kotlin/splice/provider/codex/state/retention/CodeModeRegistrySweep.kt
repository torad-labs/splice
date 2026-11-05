package splice.provider.codex.state.retention

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.diagnostics.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.stream.CodeModeNativeSessions
import kotlin.concurrent.withLock

internal class CodeModeRegistrySweep(
    private val config: CodeModeBridgeConfig,
    private val nativeSessions: CodeModeNativeSessions,
    private val access: CodeModeRegistryAccess,
    private val state: CodeModeSweepRecords,
    private val store: CodexCodeModeStore,
    private val sweeper: CodexCodeModeSweeper,
    private val cache: CodeModeRecordCache,
) : Runnable {
    override fun run() {
        nativeSessions.ended().forEach { key ->
            access.tryKey(key) { nativeSessions.closeSession(key) }
        }
        val (keys, due) = access.monitor.withLock {
            val due = store.cold.before(config.clock.millis() - config.retention.ttl.inWholeMilliseconds)
            val keys = state.records.map(CodeModeRecord::key).toSet() +
                state.history.entries.map(CodeModeExpiredSnapshot::key) + store.pendingKeys + due
            keys to due
        }
        keys.forEach { key -> sweep(key, key in due) }
        withinBounds()
        cache.trim()
    }

    private fun sweep(key: String, restore: Boolean) {
        Cancellables.runCatchingBestEffort {
            access.tryKey(key) {
                if (restore) cache.restore(key)
                val changed = sweeper.sweep(key)
                store.save(state.records, state.history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
            }
        }.exceptionOrNull()?.let { failure ->
            config.log(
                "[code-mode] conversation ${key.take(CODE_MODE_RECORD_LOG_CHARS)} not swept " +
                    "(${SafeFailureText.render(failure)}); the next sweep tries again",
            )
        }
    }

    /** The head's bounds hold on the timer too: finished conversations past them go within one sweep of
     *  losing their protection, under the same protections an admission keeps. A key whose lock is
     *  taken is passed over this sweep. */
    private fun withinBounds() {
        val passed = mutableSetOf<String>()
        while (true) {
            val key = access.monitor.withLock {
                state.retention.overBound(
                    state.records,
                    sweeper.busyKeys() + passed,
                )
            } ?: return
            var went = false
            access.tryKey(key) {
                went = state.retention.letGoOver(
                    state.records,
                    state.history,
                    key,
                    sweeper.busyKeys(),
                    config.clock.millis(),
                )
                if (went) store.save(state.records, state.history.entries, dirtyKeys = setOf(key))
            }
            if (!went) passed += key
        }
    }
}

internal data class CodeModeSweepRecords(
    val records: MutableList<CodeModeRecord>,
    val history: CodeModeExpiredHistory,
    val retention: CodeModeRecordRetention,
)

// NEW: #525 (2026-11-12): a retiring code-mode head finishes the conversations it is answering, lets go of the rest.
package splice.provider.codex.state.retention

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeRecordChange
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.CodeModeTurnLocks
import splice.provider.codex.state.diagnostics.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.state.handover.CodeModeHoldFiles
import java.io.IOException
import java.nio.channels.FileLock
import java.nio.file.Path

/**
 * #525: at a restart the retiring daemon finishes the answers already arriving while the next daemon serves new
 * requests. A code-mode conversation (one turn key, one journal) belongs to exactly one daemon at a time, because
 * nothing serializes two daemons' writes to one journal. So at the retire ([retire]) each conversation with a turn
 * queued or running here is held ([CodeModeHoldFiles]) and runs on; every other one gets what a head stop gives it
 * (its rounds stop, its cells close, its unfinished records are lost and it is saved) and is let go: this process
 * never writes its files again ([splice.provider.codex.state.handover.CodeModeWriteScope]). A held conversation gets
 * the same when its last turn here ends, and then its hold goes, so the next daemon reads it from the disk at its
 * next turn ([CodeModeAdoption]).
 *
 * A conversation whose hold cannot be taken is let go at the retire like the others, its running turn with it: it
 * stops whole, as every code-mode conversation did at a restart before. [stopRounds] stops a conversation's reading
 * rounds and lets its history go; it runs before the conversation's records are stopped, as a head stop orders them.
 */
internal class CodeModeHandover(
    private val registry: CodexCodeModeRegistry,
    private val locks: CodeModeTurnLocks,
    private val stopRounds: CodeModeSessionEnd,
    private val log: LogSink,
) {
    private val monitor = Any()
    private val holds = mutableMapOf<String, FileLock>()

    /** What else of a lost record runs: a native cell handed to the pool, which a whole stop closes all at once. */
    private val closeNative = CodeModeRecordChange { record ->
        val id = record.nativeCellId
        if (id != null) {
            val native = registry.nativeCells
            native.takeRetained(native.services.nativeSession(record.key), id)?.cell?.close()
        }
    }

    /** The head retires: called once, before the turns it does not keep are cut and before the next daemon starts.
     *  Returns the conversations it still holds. */
    fun retire(): Set<String> = synchronized(monitor) {
        val held = locks.retire(CodeModeSessionEnd(::ended)).filterTo(HashSet(), ::hold)
        registry.timed.retire()
        (registry.changes.conversations() - held).forEach(::stop)
        registry.files.writes.retire(held)
        // The next daemon's start takes what this one knows of each conversation it has just let go from this list,
        // and reads only the journals the list does not cover.
        registry.files.tell(registry.letGo)
        log("[code-mode] retire: ${held.size} conversation(s) finish here; the next daemon takes the rest")
        held
    }

    /** The last turn of [key] here ended after the retire. A held conversation gets its stop and is let go. */
    private fun ended(key: String) = synchronized(monitor) {
        val lock = holds.remove(key) ?: return@synchronized
        try {
            stop(key)
        } finally {
            registry.files.writes.release(key)
            release(lock, key)
        }
    }

    private fun stop(key: String) {
        stopRounds(key)
        Cancellables.runCatchingBestEffort { registry.changes.stopConversation(key, closeNative) }
            .exceptionOrNull()?.let { failure ->
                log(
                    "[code-mode] conversation ${key.take(CODE_MODE_RECORD_LOG_CHARS)} not saved at the hand-over " +
                        "(${SafeFailureText.render(failure)}): the next daemon reads its last saved state",
                )
            }
    }

    private fun hold(key: String): Boolean {
        val hold = holdPath(key)
        val lock = try {
            SecureFile.ownerOnlyDirectory(hold.parent)?.let { open ->
                log("[code-mode] ${hold.parent} open to others: $open")
            }
            CodeModeHoldFiles.take(hold)
        } catch (failure: IOException) {
            log("[code-mode] hold ${hold.fileName} not taken (${SafeFailureText.render(failure)})")
            null
        }
        if (lock == null) {
            val named = key.take(CODE_MODE_RECORD_LOG_CHARS)
            log("[code-mode] conversation $named cannot be held: it stops now, as at a stop")
            return false
        }
        holds[key] = lock
        return true
    }

    private fun release(lock: FileLock, key: String) {
        try {
            CodeModeHoldFiles.release(lock, holdPath(key))
        } catch (failure: IOException) {
            val named = key.take(CODE_MODE_RECORD_LOG_CHARS)
            log("[code-mode] hold of $named not removed (${SafeFailureText.render(failure)})")
        }
    }

    private fun holdPath(key: String): Path = CodeModeHoldFiles.beside(registry.files.path(key))
}

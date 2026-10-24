package splice.provider.codex.stream

import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.util.concurrent.ConcurrentHashMap

/** Live engines remain session-local. Durable ids describe lost engines after restart; they never replay source. */
internal class CodeModeNativeCells : AutoCloseable {
    private val cells = ConcurrentHashMap<Pair<String, String>, CodeModeHeldNativeCell>()
    private val completed = ConcurrentHashMap<Pair<String, String>, String>()

    fun retain(key: String, cell: NativeCodeModeCell, notificationCallId: String) {
        val held = CodeModeHeldNativeCell(cell, notificationCallId)
        val previous = cells.putIfAbsent(key to cell.cellId, held)
        check(previous == null || previous.cell === cell) { "Native cell identity is already owned" }
    }

    fun take(key: String, id: String): CodeModeHeldNativeCell = cells.remove(key to id)
        ?: completed[key to id]?.let { CodeModeHeldNativeCell(CodeModeTerminalNativeCell(id), it) }
        ?: error("Native cell is unavailable; its source was not rerun")

    fun completed(key: String, id: String, notificationCallId: String) {
        completed[key to id] = notificationCallId
    }

    fun closeSession(key: String) {
        val ended = cells.entries.filter { it.key.first == key }
        ended.forEach { (id, held) -> if (cells.remove(id, held)) held.cell.close() }
        completed.keys.removeIf { it.first == key }
    }

    override fun close() {
        val ending = cells.values.toList()
        cells.clear()
        completed.clear()
        ending.forEach { it.cell.close() }
    }
}

/** Foreground records may close after a yield. Only an explicit handoff transfers engine ownership to the pool. */
internal class CodeModeNativeObservation(
    private val key: String,
    private val cell: NativeCodeModeCell,
    val invocation: CodeModeNativeInvocation,
    private val retained: CodeModeNativeCells,
    val notificationCallId: String,
) : CodeModeCell {
    private var handedOff = false
    private var initial = true
    private var deferred: CodeModeStep.Yielded? = null
    private val notifications = StringBuilder()
    val cellId: String get() = cell.cellId

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        cell.submitResults(results)
        if (initial && invocation.terminate) {
            initial = false
            return cell.terminate().also { retained.completed(key, cellId, notificationCallId) }
        }
        initial = false
        val queued = deferred
        deferred = null
        val step = queued ?: cell.observe(invocation.options.yieldTimeMs)
        if (step is CodeModeStep.Completed) retained.completed(key, cellId, notificationCallId)
        return step
    }

    fun notification(text: String) {
        notifications.append(text)
    }

    fun deliveredText(): String = notifications.toString()

    fun delivered() {
        notifications.setLength(0)
    }

    fun defer(step: CodeModeStep.Yielded) {
        check(deferred == null) { "Native foreground observation was already deferred" }
        deferred = step
    }

    fun handOff() {
        retained.retain(key, cell, notificationCallId)
        handedOff = true
    }

    override fun close() {
        if (!handedOff) cell.close()
    }
}

internal data class CodeModeHeldNativeCell(val cell: NativeCodeModeCell, val notificationCallId: String)

/** Only a positively recorded terminal native cell can use this empty exhausted observation. */
private class CodeModeTerminalNativeCell(override val cellId: String) : NativeCodeModeCell {
    override suspend fun submitResults(results: List<CodeModeResult>) {
        require(results.isEmpty()) { "A completed native cell owns no pending callback" }
    }
    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return CodeModeStep.Completed("")
    }
    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = CodeModeStep.Completed("")
    override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)
    override fun close() = Unit
}

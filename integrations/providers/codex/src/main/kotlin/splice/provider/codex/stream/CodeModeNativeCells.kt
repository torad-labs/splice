package splice.provider.codex.stream

import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.util.concurrent.ConcurrentHashMap

/** Live engines remain session-local. Durable ids describe lost engines after restart; they never replay source. */
internal class CodeModeNativeCells : AutoCloseable {
    private val cells = ConcurrentHashMap<Pair<String, String>, CodeModeHeldNativeCell>()

    fun retain(key: String, cell: NativeCodeModeCell, notificationCallId: String) {
        val held = CodeModeHeldNativeCell(cell, notificationCallId)
        val previous = cells.putIfAbsent(key to cell.cellId, held)
        check(previous == null || previous.cell === cell) { "Native cell identity is already owned" }
    }

    fun take(key: String, id: String): CodeModeHeldNativeCell =
        checkNotNull(cells.remove(key to id)) { "Native cell is unavailable; its source was not rerun" }

    override fun close() {
        val ending = cells.values.toList()
        cells.clear()
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
            return cell.terminate()
        }
        initial = false
        val queued = deferred
        deferred = null
        return queued ?: cell.observe(invocation.options.yieldTimeMs)
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

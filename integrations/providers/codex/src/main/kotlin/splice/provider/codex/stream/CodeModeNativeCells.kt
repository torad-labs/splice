package splice.provider.codex.stream

import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.stream.interrupt.CodeModeNativeInterrupts
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeInvocationAction
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** Live engines remain session-local. Durable ids describe lost engines after restart; they never replay source. */
internal class CodeModeNativeCells : AutoCloseable {
    private val ownership = Any()
    private val cells = ConcurrentHashMap<Pair<String, String>, CodeModeHeldNativeCell>()
    private val completed = ConcurrentHashMap<Pair<String, String>, String>()

    /** Every cell this process handed to the pool since its session opened, including one a wait now observes. */
    private val owned = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val sessions = ConcurrentHashMap<String, String>()
    private val aliases = ConcurrentHashMap<String, String>()
    val interrupts = CodeModeNativeInterrupts()

    suspend fun interrupt(key: String, clientSessionId: String): splice.provider.codex.CodeModeInterruptOutcome {
        val selected = synchronized(ownership) {
            if (sessions[key] == clientSessionId) interrupts.snapshot(key) else null
        } ?: return splice.provider.codex.CodeModeInterruptOutcome.Unavailable
        val cells = interrupts.interrupt(selected).map { interrupted ->
            if (interrupted.completed) {
                val held = interrupted.owner
                completed(key, held.cell.cellId, held.notificationCallId)
            }
            interrupted.result
        }
        return splice.provider.codex.CodeModeInterruptOutcome.Interrupted(cells)
    }

    fun trackSession(recordKey: String, key: String, clientSessionId: String) = synchronized(ownership) {
        aliases[recordKey] = key
        val prior = sessions.putIfAbsent(key, clientSessionId)
        require(prior == null || prior == clientSessionId) { "Native session ownership changed" }
    }

    fun nativeSession(key: String): String = aliases[key] ?: key

    fun clientSession(key: String): String? = sessions[nativeSession(key)]

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    fun endedSessions(alive: splice.provider.codex.state.lease.CodeModeSessionAlive): List<String> =
        sessions.filterValues { alive(it) == false }.keys.toList()

    fun retain(key: String, cell: NativeCodeModeCell, notificationCallId: String) {
        val held = CodeModeHeldNativeCell(cell, notificationCallId)
        val previous = cells.putIfAbsent(key to cell.cellId, held)
        check(previous == null || previous.cell === cell) { "Native cell identity is already owned" }
        owned += key to cell.cellId
    }

    /** Whether this process ever held [id]'s engine; a durable running id it never held was lost with its process. */
    fun owns(key: String, id: String): Boolean = (key to id) in owned

    fun takeRetained(key: String, id: String): CodeModeHeldNativeCell? = cells.remove(key to id)

    fun take(key: String, id: String): CodeModeHeldNativeCell = takeRetained(key, id)
        ?: CodeModeHeldNativeCell(CodeModeMissingNativeCell(id), completed[key to id].orEmpty())

    fun completed(key: String, id: String, notificationCallId: String) {
        completed[key to id] = notificationCallId
        interrupts.completed(key, id)
        cells.remove(key to id)
    }

    fun closeSession(recordKey: String) = synchronized(ownership) {
        val key = nativeSession(recordKey)
        interrupts.closeSession(key)
        val ended = cells.entries.filter { it.key.first == key }
        ended.forEach { (id, held) -> if (cells.remove(id, held)) held.cell.close() }
        completed.keys.removeIf { it.first == key }
        owned.removeIf { it.first == key }
        sessions.remove(key)
        aliases.entries.removeIf { it.value == key }
    }

    override fun close() = synchronized(ownership) {
        interrupts.close()
        val ending = cells.values.toList()
        cells.clear()
        completed.clear()
        owned.clear()
        sessions.clear()
        aliases.clear()
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
    val toolPolicy: CodeModeToolPolicy = CodeModeToolPolicy(),
) : CodeModeCell {
    private var handedOff = false
    private var initial = true
    private var deferred: CodeModeStep.Yielded? = null
    private val notifications = StringBuilder()
    val cellId: String get() = cell.cellId

    override suspend fun <T> withInvocationContext(id: String, action: CodeModeInvocationAction<T>): T =
        cell.withInvocationContext(id, action)

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        cell.submitResults(results)
        if (initial && invocation.terminate) {
            initial = false
            return observed(cell.terminate())
        }
        initial = false
        val queued = deferred
        deferred = null
        val step = queued ?: invocation.options.unsignedYieldTimeMs?.let { cell.observe(it) }
            ?: cell.observe(invocation.options.yieldTimeMs)
        return observed(step)
    }

    private fun observed(step: CodeModeStep): CodeModeStep {
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

    /** Foreground preemption closes its observation, while this retained owner keeps the same native cell. */
    suspend fun yieldObservation() {
        cell.yieldObservation()
    }

    override fun close() {
        if (!handedOff) {
            retained.interrupts.release(key, cell)
            cell.close()
        }
    }
}

internal data class CodeModeHeldNativeCell(val cell: NativeCodeModeCell, val notificationCallId: String)

/** Runtime service::missing_cell_response applies equally to unknown, exhausted and wrong-session ids. */
private class CodeModeMissingNativeCell(override val cellId: String) : NativeCodeModeCell {
    override suspend fun submitResults(results: List<CodeModeResult>) {
        require(results.isEmpty()) { "A completed native cell owns no pending callback" }
    }
    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return missing()
    }
    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = missing()
    override suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep = missing()
    override suspend fun terminate(): CodeModeStep = missing()
    override fun close() = Unit

    private fun missing(): CodeModeStep = CodeModeStep.Completed("", error = "exec cell $cellId not found")
}

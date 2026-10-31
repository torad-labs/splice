// NEW: dispatched source cells own their lease until the host acknowledges context close.
package splice.codemode.host

import kotlinx.serialization.json.JsonObject
import splice.codemode.JvmCodeModeCell
import splice.codemode.ReleaseCodeModeCell
import splice.codemode.wire.CodeModeFrames
import splice.upstream.codemode.CodeModeCell
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class CodeModeCellStarts(
    private val pool: CodeModeHostPool,
    private val closed: AtomicBoolean,
) {
    private val cells: MutableSet<JvmCodeModeCell> = ConcurrentHashMap.newKeySet()

    suspend fun start(lease: CodeModePoolLease, frame: JsonObject, tools: Set<String>): CodeModeCell {
        val pipe = lease.pipe
        var started = false
        try {
            val initial = CodeModeFrames.parseReply(pipe.exchange(frame), tools, 1)
            val cell = JvmCodeModeCell(
                pipe,
                initial,
                tools,
                ReleaseCodeModeCell { cell ->
                    pool.release(lease)
                    cells.remove(cell)
                },
            )
            cells.add(cell)
            pipe.afterExit { cell.stop() }
            if (closed.get()) {
                cell.stop()
                throw CodeModeWorkerLostException()
            }
            started = true
            return cell
        } finally {
            if (!started) {
                pipe.afterExit { pool.release(lease) }
                pipe.close()
            }
        }
    }

    fun closeAll() {
        cells.forEach(JvmCodeModeCell::stop)
    }
}

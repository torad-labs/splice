// NEW: the existing cell-owned channel stays separate from shared-host reply routing and generation lifetime.
package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.atomic.AtomicBoolean

internal class HostCellChannel(
    private val host: SharedWorkerChannel,
    private val id: Long,
    private val exited: CompletableDeferred<Unit>,
    private val session: Long,
) : CellChannel {
    private val closed = AtomicBoolean()

    override suspend fun exchange(frame: JsonObject): JsonObject {
        if (host.isClosed) throw CodeModeWorkerLostException()
        check(!closed.get()) { "Code-mode cell is closed" }
        return host.exchange(id, frame, session)
    }

    override fun afterExit(action: WorkerExited) {
        exited.invokeOnCompletion { action() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) host.closeCell(id, session)
    }
}

// NEW: identity-bearing host placements and session leases do not use mutable structural equality.
package splice.codemode.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import splice.codemode.CellChannel
import splice.codemode.SharedWorkerChannel

internal fun interface CodeModeHostStart {
    suspend fun open(): SharedWorkerChannel
}

internal class CodeModePoolHost {
    var boot: Deferred<SharedWorkerChannel>? = null
    val sessions = mutableSetOf<CodeModePoolSession>()

    fun load(): Int = sessions.size
}

internal class CodeModePoolSession(val key: String, val id: Long, val host: CodeModePoolHost) {
    val gate = Mutex()
    val pipes = mutableSetOf<CellChannel>()
    val retired = CompletableDeferred<Unit>()
    var users = 0
    var lastUse = 0L

    @Volatile var initialized = false
    var closing = false

    fun used(at: Long) {
        lastUse = at
    }
}

internal data class CodeModePoolLease(val session: CodeModePoolSession, val pipe: CellChannel)

internal sealed class CodeModePlacement {
    data class Acquired(val session: CodeModePoolSession) : CodeModePlacement()
    data class Reclaim(val session: CodeModePoolSession) : CodeModePlacement()
}

package splice.provider.codex.stream

import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.lease.CodeModeSessionAlive

/** Record cleanup does not imply death of a native client session or its stored values and yielded cells. */
internal class CodeModeNativeSessions(
    private val cells: CodeModeNativeCells,
    private val alive: CodeModeSessionAlive,
    private val close: CodeModeSessionEnd,
) {
    fun ended(): List<String> = cells.endedSessions(alive)

    fun closeSession(key: String) {
        val owner = cells.nativeSession(key)
        val client = cells.clientSession(key)
        if (client != null && alive(client) != false) return
        cells.closeSession(key)
        close(owner)
    }
}

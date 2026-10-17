// NEW: holds the current head run's code-mode runtime, opened lazily and closed at head stop.
package splice.provider.codex.stream

import splice.provider.codex.CodeModeRuntimes
import splice.upstream.codemode.CodeModeRuntime

/** The current head run's runtime: opened on the first script after a start, closed at head stop. */
internal class CodeModeRuntimeRun(private val runtimes: CodeModeRuntimes) {
    private val monitor = Any()
    private var current: CodeModeRuntime? = null

    fun runtime(): CodeModeRuntime = synchronized(monitor) { current ?: runtimes.open().also { current = it } }

    fun closeSession(key: String) {
        synchronized(monitor) { current?.closeSession(key) }
    }

    fun end() {
        val ending: CodeModeRuntime? = synchronized(monitor) { current.also { current = null } }
        ending?.close()
    }
}

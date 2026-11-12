// NEW: rejected-cell disposal cannot prevent the registry from persisting its lost record.
package splice.provider.codex.state

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.codemode.CodeModeCell

internal class CodeModeCellCleanup(private val log: LogSink) {
    fun rejected(cell: CodeModeCell?) {
        Cancellables.runCatchingBestEffort { cell?.close() }.onFailure {
            log("[code-mode] rejected cell close failed (${SafeFailureText.render(it)})")
        }
    }
}

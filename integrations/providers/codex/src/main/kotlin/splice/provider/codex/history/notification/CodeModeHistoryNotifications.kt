package splice.provider.codex.history.notification

import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.history.CodeModeUpstreamLedger

/** Appends accepted input to the same branch cursor that captures new model requests. */
internal class CodeModeHistoryNotifications(private val ledger: CodeModeUpstreamLedger) {
    fun inject(context: CodeModeRunContext, record: CodeModeRecord) = synchronized(context.postPosition.monitor) {
        context.postId?.let { ledger.inject(context.key, it, record) }
    }
}

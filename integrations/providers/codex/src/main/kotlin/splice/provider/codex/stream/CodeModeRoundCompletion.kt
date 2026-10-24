// NEW: source completion settles non-cancellable cleanup before a client may consume the outcome.
package splice.provider.codex.stream

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.TurnEnd

/** Attributes an unnamed reader failure before its completion is observable. */
internal fun interface CodeModeReaderDeath {
    operator fun invoke(cause: Throwable?)
}

/** Completion is a separate lifetime boundary from the reader's Deferred result. */
internal class CodeModeRoundCompletion(
    private val log: LogSink,
    private val settled: CompletableDeferred<Unit>,
    private val died: CodeModeReaderDeath,
) {
    fun completed(scope: CoroutineScope, cause: Throwable?, end: TurnEnd) {
        val handler = CoroutineExceptionHandler { _, failure ->
            log("[code-mode] source completion callback failed: ${SafeFailureText.render(failure)}")
        }
        scope.launch(NonCancellable + handler, CoroutineStart.UNDISPATCHED) {
            try {
                died(cause)
            } finally {
                Cancellables.withCleanup({ settled.complete(Unit) }) { end.ended() }
            }
        }
    }
}

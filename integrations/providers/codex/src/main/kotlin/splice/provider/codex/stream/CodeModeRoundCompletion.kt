// NEW: source completion settles non-cancellable cleanup before delivering the outcome to its reading client step.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModePersistenceException
import splice.upstream.TurnEnd
import splice.upstream.transport.UpstreamEnding

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
    /** Set once before the reader completes; delivered in the reading turn's context after cleanup settles. */
    @Volatile private var ending: UpstreamEnding? = null

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

    fun ended(ending: UpstreamEnding): TurnOutcome.Failure {
        this.ending = ending
        return CodeModeEndings.placeholder()
    }

    suspend fun outcome(reader: Deferred<TurnOutcome>): TurnOutcome {
        val awaited = try {
            Cancellables.runCatchingBestEffort { reader.await() }
        } catch (error: CancellationException) {
            // Reader cancellation is its failed response; cancellation of this client step still propagates.
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        }
        // Deferred completion precedes the dead reader's registry write. Never continue before cleanup settles.
        settled.await()
        // The reading turn, not the reader, is the one the ending ends: its slot is in this call's context.
        ending?.let { CodeModeEndings.settle(it) }
        val failure = awaited.exceptionOrNull() ?: return awaited.getOrThrow()
        if (failure is CodeModePersistenceException) throw failure
        return CodeModeReaderFailures.outcome()
    }
}

// NEW: source completion settles non-cancellable cleanup before delivering the outcome to its reading client step.
package splice.provider.codex.stream.reader

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
import splice.provider.codex.stream.CodeModeCutClaim
import splice.provider.codex.stream.CodeModeEndings
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeSwitchingSink
import splice.provider.codex.stream.source.CodeModeSourceCapture
import splice.upstream.RoundResult
import splice.upstream.TurnEnd
import splice.upstream.transport.UpstreamEnding

/** Attributes an unnamed reader failure before its completion is observable. */
internal fun interface CodeModeReaderDeath {
    operator fun invoke(cause: Throwable?)
}

/** Parsed terminals settle the source and its posting row before completion becomes observable. */
internal class CodeModeReaderSettlement(
    private val capture: CodeModeSourceCapture,
    private val lifecycle: CodeModeRoundLifecycle,
    private val outcomes: CodeModeReaderOutcomes,
    private val billing: CodeModeRoundBilling,
    private val cut: CodeModeCutClaim,
) : CodeModeReaderDeath {
    internal var beforeSettle: Runnable? = null

    /** Set once before the reader completes; delivered in the reading turn's context after cleanup settles. */
    @Volatile private var ending: UpstreamEnding? = null

    override fun invoke(cause: Throwable?) {
        try {
            outcomes.died(cause)
        } finally {
            billing.settle(readerEnd = true, clientCut = cut.client)
        }
    }

    suspend fun finish(outcome: TurnOutcome, switching: CodeModeSwitchingSink): TurnOutcome {
        outcomes.upstreamEnded = true
        lifecycle.delivered(switching.confirmedText())
        val result = outcomes.finish(outcome)
        beforeSettle?.run()
        billing.settle(readerEnd = false, clientCut = cut.client)
        return result
    }

    /** A refusal belongs to the client turn that reads this round, with the source transition settled first. */
    fun ended(ending: UpstreamEnding): TurnOutcome.Failure = synchronized(lifecycle) {
        outcomes.upstreamEnded = true
        capture.source.fail(SOURCE_REFUSED)
        if (lifecycle.stoppedByHead) throw CancellationException(CodeModeReaderFailures.HEAD_STOPPED)
        lifecycle.localFailure?.let { return@synchronized it }
        // A record's next client step reads the source's outcome, as a torn source's does.
        lifecycle.sourceInterrupted = capture.unfinished
        capture.interrupted(SOURCE_REFUSED)
        this.ending = ending
        CodeModeEndings.placeholder()
    }

    suspend fun settleEnding() {
        ending?.let { CodeModeEndings.settle(it) }
    }
}

/** Completion is a separate lifetime boundary from the reader's Deferred result. */
internal class CodeModeRoundCompletion(
    private val log: LogSink,
    private val settled: CompletableDeferred<Unit>,
    private val settlement: CodeModeReaderSettlement,
) {
    fun completed(scope: CoroutineScope, cause: Throwable?, end: TurnEnd) {
        val handler = CoroutineExceptionHandler { _, failure ->
            log("[code-mode] source completion callback failed: ${SafeFailureText.render(failure)}")
        }
        scope.launch(NonCancellable + handler, CoroutineStart.UNDISPATCHED) {
            try {
                settlement(cause)
            } finally {
                Cancellables.withCleanup({ settled.complete(Unit) }) { end.ended() }
            }
        }
    }

    /** Record intercepted endings before settling the source, just as an ordinary upstream post does. */
    suspend fun settleRead(
        post: CodeModeRedirectablePost,
        posted: RoundResult,
        switching: CodeModeSwitchingSink,
    ): TurnOutcome = when (posted) {
        is RoundResult.Ended -> {
            post.record(posted.ending)
            settlement.ended(posted.ending)
        }
        is RoundResult.Outcome -> settlement.finish(posted.outcome, switching)
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
        settlement.settleEnding()
        val failure = awaited.exceptionOrNull() ?: return awaited.getOrThrow()
        if (failure is CodeModePersistenceException) throw failure
        return CodeModeReaderFailures.outcome()
    }
}

private const val SOURCE_REFUSED = "upstream refused the source request; source was not rerun"

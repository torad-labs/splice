// NEW: the terminal upstream outcome owns final source identity and continuity, not a client step.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.RedirectableRoundPost
import splice.upstream.TurnEnd
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException

internal class CodeModeLiveRound(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
    sink: WireSink,
) {
    private val capture = CodeModeSourceCapture(config, registry, wire, admission)
    val source = capture.source
    val ready = capture.ready
    val switching = CodeModeSwitchingSink(sink, CodeModeSourceObserver(::observe))
    private val record: CodeModeRecord? get() = capture.record
    private var finished: Deferred<TurnOutcome>? = null

    private val lifecycle = Any()
    private var headStopped = false

    @Volatile private var upstreamEnded = false

    @Volatile var sourceInterrupted = false
        private set

    @Volatile var localFailure: TurnOutcome.Failure? = null
        private set

    fun start(scope: CoroutineScope, post: RedirectableRoundPost, body: String, end: TurnEnd) {
        check(finished == null)
        finished = scope.async(start = CoroutineStart.UNDISPATCHED) {
            switching.ownedBy(this)
            try {
                val outcome = post.into(body, switching)
                upstreamEnded = true
                finish(outcome)
            } catch (error: CancellationException) {
                cancelled(error)
                throw error
            } catch (error: IOException) {
                failed(error)
            } catch (error: CodeModePersistenceException) {
                failed(error)
            } catch (error: IllegalStateException) {
                failed(error)
            } catch (error: IllegalArgumentException) {
                failed(error)
            } finally {
                if (!ready.isCompleted) ready.complete(null)
            }
        }.also { reader ->
            reader.invokeOnCompletion { cause -> completed(scope, cause, end) }
        }
    }

    /** Non-suspending cleanup runs immediately even on cancellation, outside coroutine completion machinery. */
    private fun completed(scope: CoroutineScope, cause: Throwable?, end: TurnEnd) {
        val handler = CoroutineExceptionHandler { _, failure ->
            config.log("[code-mode] source completion callback failed: ${SafeFailureText.render(failure)}")
        }
        scope.launch(NonCancellable + handler, CoroutineStart.UNDISPATCHED) {
            try {
                died(cause)
            } finally {
                end.ended()
            }
        }
    }

    /** The reader ended on a throwable none of [start]'s catches names (Oct 2: a ConcurrentModificationException
     *  out of the record's save). Its source had no terminal, so the cell reading it waited forever and nothing
     *  was logged. The source fails, the record is lost as [failed] loses it, and the throwable's class is named. */
    private fun died(cause: Throwable?) {
        val unnamed = cause?.takeUnless { it is CancellationException || it is CodeModePersistenceException } ?: return
        upstreamEnded = true
        config.log("[code-mode] upstream source reader died (${unnamed::class.simpleName}): $SOURCE_FAILED")
        source.fail(SOURCE_FAILED)
        synchronized(lifecycle) {
            if (headStopped) return
            val current = record?.takeUnless(CodeModeRecord::terminal) ?: return
            Cancellables.runCatchingBestEffort { registry.lose(current, SOURCE_FAILED) }.onFailure { failure ->
                config.log("[code-mode] the dead reader's record was not saved as lost (${failure::class.simpleName})")
            }
        }
    }

    private fun finish(outcome: TurnOutcome) = synchronized(lifecycle) {
        if (headStopped) throw CancellationException("code-mode head stopped")
        localFailure?.let { return@synchronized it }
        sourceInterrupted = record != null && (outcome as? TurnOutcome.Failure)?.cause in SOURCE_TEAR_CAUSES
        Cancellables.runCatchingBestEffort { capture.finish(outcome) }
            .getOrElse { return@synchronized reject(it) }
        if (sourceInterrupted && outcome is TurnOutcome.Failure) {
            outcome.copy(
                cause = FailureCause.UPSTREAM_CONN_RESET,
                phase = FailurePhase.MID_OUTPUT,
                deterministic = false,
                partial = null,
            )
        } else {
            outcome
        }
    }

    private fun cancelled(error: CancellationException) {
        upstreamEnded = true
        source.fail("upstream source cancelled; source was not rerun")
        synchronized(lifecycle) {
            if (!headStopped) {
                record?.takeUnless(CodeModeRecord::terminal)
                    ?.let { registry.lose(it, "upstream source cancelled; source was not rerun", error) }
            }
        }
    }

    private fun failed(error: Exception): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        if (headStopped) {
            source.fail(SOURCE_FAILED)
            throw CancellationException("code-mode head stopped", error)
        }
        localFailure?.let { return@synchronized it }
        val detail = SOURCE_FAILED
        sourceInterrupted = record != null && error is IOException && error !is CodeModePersistenceException
        source.fail(detail)
        if (error is CodeModePersistenceException) {
            if (!ready.isCompleted) ready.completeExceptionally(error)
            throw error
        }
        record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, detail) }
        TurnOutcome.Failure(
            detail,
            cause = if (sourceInterrupted) FailureCause.UPSTREAM_CONN_RESET else FailureCause.INTERNAL,
            phase = FailurePhase.MID_OUTPUT,
        )
    }

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = synchronized(lifecycle) {
        val current = record ?: return@synchronized false
        if (current.sessionId != turn.sessionId || current.key != CodeModeTurnIdentity().turnKey(turn)) {
            return@synchronized false
        }
        val ids = registry.changes.clientIds(current)
        turn.toolResults.any { it.id in ids }
    }

    /** A reader that died on an unnamed throwable ends the round as a torn transport does, with [failed]'s
     *  outcome, never by throwing its throwable through the next client step. */
    suspend fun outcome(): TurnOutcome {
        val reader = checkNotNull(finished)
        val awaited = Cancellables.runCatchingBestEffort { reader.await() }
        val failure = awaited.exceptionOrNull() ?: return awaited.getOrThrow()
        if (failure is CodeModePersistenceException) throw failure
        return TurnOutcome.Failure(SOURCE_FAILED, cause = FailureCause.INTERNAL, phase = FailurePhase.MID_OUTPUT)
    }

    fun cancel() {
        if (!upstreamEnded) finished?.cancel()
    }

    /** The synchronous registry stop owns persistence; a cancelled old reader cannot overwrite the next head. */
    fun stop() {
        synchronized(lifecycle) { headStopped = true }
        cancel()
    }

    /** Observer faults belong to splice. They never unwind through a transport's generic stream catch. */
    private fun observe(event: CustomToolSource) = synchronized(lifecycle) {
        if (headStopped) throw CancellationException("code-mode head stopped")
        if (localFailure != null) return@synchronized
        Cancellables.runCatchingBestEffort { capture.observe(event) }.onFailure(::reject)
    }

    private fun reject(error: Throwable): TurnOutcome.Failure {
        val failure = CodeModeRejection.outcome(error)
        localFailure = failure
        sourceInterrupted = false
        // SAFE-RENDER-EXEMPT[2026-10-03]: failure is the domain outcome from CodeModeRejection.outcome, whose throwable input uses SafeFailureText.render or CodeModePersistenceException.outcome's safe literals.
        source.fail(failure.message)
        try {
            localFailure = CodeModeRejection.lose(record, failure, registry)
        } finally {
            ready.complete(null)
        }
        return checkNotNull(localFailure)
    }
}

private const val SOURCE_FAILED = "upstream source failed; source was not rerun"
private val SOURCE_TEAR_CAUSES = setOf(
    FailureCause.UPSTREAM_CONN_RESET,
    FailureCause.UPSTREAM_TRUNCATED,
    FailureCause.UPSTREAM_STALLED,
)

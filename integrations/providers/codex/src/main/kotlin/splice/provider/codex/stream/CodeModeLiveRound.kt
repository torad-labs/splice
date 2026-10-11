// NEW: the terminal upstream outcome owns final source identity and continuity, not a client step.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.HeadStopSignal
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.RoundResult
import splice.upstream.TurnEnd
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamEnding
import java.io.IOException

internal class CodeModeLiveRound(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
    sink: WireSink,
    private val headStop: HeadStopSignal? = null,
    recovery: CodeModeRecoveryHistory? = null,
) {
    /** Runs just before a parsed terminal settles. Production never sets it: billing tests hold that window. */
    internal var beforeSettle: Runnable? = null
    private val capture = CodeModeSourceCapture(config, registry, wire, admission, recovery)
    val source = capture.source
    val ready = capture.ready
    private val record: CodeModeRecord? get() = capture.record
    private val lifecycle = CodeModeRoundLifecycle()
    val billing = CodeModeRoundBilling(lifecycle, registry, CodeModeRoundRecord { record }, config.log)
    val switching = CodeModeSwitchingSink(sink, observer = CodeModeSourceObserver(::observe))
    val cut = CodeModeCutClaim()
    val key: String? get() = record?.key
    internal var finished: Deferred<TurnOutcome>? = null
        private set
    private val settled = CompletableDeferred<Unit>()
    private val completion = CodeModeRoundCompletion(
        config.log,
        settled,
        CodeModeReaderDeath { cause ->
            try {
                died(cause)
            } finally {
                billing.settle(readerEnd = true, clientCut = cut.client)
            }
        },
    )

    @Volatile var unexpectedDeath: Boolean = false
        private set

    @Volatile private var headStopped = false
    private val stoppedByHead: Boolean get() = headStopped || headStop?.isStopping == true

    @Volatile internal var upstreamEnded = false

    /** The upstream delivered the round's terminal; its outcome has not reached [settleRead] yet. A client step that
     *  fails in that gap cuts nothing: the source finished. */
    @Volatile private var terminalSeen = false
        private set

    @Volatile private var executionLost = false

    @Volatile var sourceInterrupted = false
        private set

    @Volatile var localFailure: TurnOutcome.Failure? = null
        private set

    /** The ending the upstream gave the source post, when it gave no outcome. Set once, before the reader completes. */
    @Volatile private var ending: UpstreamEnding? = null

    /** The round lost the record and closed its cell without a transport tear: its terminal did not certify the
     *  admitted source, or its reader failed on splice's own non-IO fault. Only the step that was advancing that cell
     *  reads it; a later step continues on the client's history. */
    val sourceLost: Boolean get() = executionLost || capture.disposed

    /** The round closed a live cell under its client steps: [sourceLost], or a reader that died on an unnamed
     *  throwable. A step holding or acquiring that cell ends as a torn source's step does, never as invalid_request. */
    val closedLiveCell: Boolean get() = sourceLost || unexpectedDeath

    /** The permanent upstream failure that ended this round before its exec source completed. A client step whose cell
     *  that loss closed ends with it as it is, since no retry can change it. */
    @Volatile var permanentEnding: TurnOutcome.Failure? = null
        private set

    fun start(scope: CoroutineScope, post: CodeModeRedirectablePost, body: CodeModeBody, end: TurnEnd) {
        check(finished == null)
        billing.start(post.postingRow)
        finished = scope.async(start = CoroutineStart.UNDISPATCHED) {
            switching.ownedBy(this)
            try {
                settleRead(post.into(body, switching))
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
            reader.invokeOnCompletion { cause -> completion.completed(scope, cause, end) }
        }
    }

    /** What the reader's post gave: an outcome the source finishes on, or an upstream ending the turn ends on. */
    private suspend fun settleRead(posted: RoundResult): TurnOutcome = when (posted) {
        is RoundResult.Ended -> ended(posted.ending)
        is RoundResult.Outcome -> {
            upstreamEnded = true
            val result = finish(posted.outcome)
            beforeSettle?.run()
            billing.settle(readerEnd = false, clientCut = cut.client)
            result
        }
    }

    /** The reader ended on a throwable none of [start]'s catches names (Oct 2: a ConcurrentModificationException
     *  out of the record's save). Its source had no terminal, so the cell reading it waited forever and nothing
     *  was logged. The source fails, the record is lost as [failed] loses it, and the throwable's class is named. */
    private fun died(cause: Throwable?) {
        val unnamed = cause?.takeUnless {
            it is CancellationException || it is CodeModePersistenceException
        } ?: return
        upstreamEnded = true
        unexpectedDeath = true
        config.log("[code-mode] upstream source reader died (${unnamed::class.simpleName}): $SOURCE_FAILED")
        source.fail(SOURCE_FAILED)
        synchronized(lifecycle) {
            if (stoppedByHead) return
            val current = record?.takeUnless(CodeModeRecord::terminal) ?: return
            Cancellables.runCatchingBestEffort { registry.lose(current, SOURCE_FAILED) }.onFailure { failure ->
                config.log("[code-mode] the dead reader's record was not saved as lost (${failure::class.simpleName})")
            }
        }
    }

    private fun finish(outcome: TurnOutcome) = synchronized(lifecycle) {
        billing.reported(outcome)
        if (stoppedByHead) throw CancellationException(HEAD_STOPPED)
        localFailure?.let { return@synchronized it }
        if (record?.terminal() == true) {
            executionLost = true
            source.fail(SOURCE_DISPOSED)
            return@synchronized outcome
        }
        sourceInterrupted = record != null && (outcome as? TurnOutcome.Failure)?.cause in SOURCE_TEAR_CAUSES
        // The capture loses a source its terminal does not certify, which closes the cell a client step may still be
        // advancing. Set first, so that step ends as a torn source's step does, or with a permanent failure as it is.
        executionLost = !sourceInterrupted && capture.uncertified(outcome) != null
        permanentEnding = (outcome as? TurnOutcome.Failure)?.takeIf { sourceLost && it.traits.permanent }
        Cancellables.runCatchingBestEffort { capture.finish(outcome) }
            .getOrElse { return@synchronized reject(it) }
        if (sourceInterrupted && outcome is TurnOutcome.Failure) {
            outcome.copy(
                cause = FailureCause.UPSTREAM_CONN_RESET,
                phase = FailurePhase.MID_OUTPUT,
                traits = outcome.traits.copy(deterministic = false),
                partial = null,
            )
        } else {
            outcome
        }
    }

    private fun cancelled(error: CancellationException) {
        upstreamEnded = true
        source.fail("splice code-mode source reader cancelled; source was not rerun")
        synchronized(lifecycle) {
            if (!stoppedByHead) {
                record?.takeUnless(CodeModeRecord::terminal)
                    ?.let { registry.lose(it, "splice code-mode source reader cancelled; source was not rerun", error) }
            }
        }
    }

    private fun failed(error: Exception): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        if (stoppedByHead) {
            source.fail(SOURCE_FAILED)
            throw CancellationException(HEAD_STOPPED, error)
        }
        localFailure?.let { return@synchronized it }
        val detail = SOURCE_FAILED
        if (error is CodeModePersistenceException) {
            source.fail(detail)
            if (!ready.isCompleted) ready.completeExceptionally(error)
            throw error
        }
        sourceInterrupted = record != null && error is IOException
        // Splice's own non-IO fault loses the record below and closes the cell a client step may be advancing, as an
        // uncertified source does. Set before the source fails, so that step ends as a torn source's step does.
        executionLost = !sourceInterrupted && record?.terminal() == false
        source.fail(detail)
        record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, detail) }
        TurnOutcome.Failure(
            detail,
            cause = if (sourceInterrupted) FailureCause.UPSTREAM_CONN_RESET else FailureCause.INTERNAL,
            phase = FailurePhase.MID_OUTPUT,
        )
    }

    /** The upstream ended the source post without an outcome (the retry loop gave up on it, it had no credentials, it
     *  tore, or sent a frame over the limit). That ending is the client turn's ending, classified where a plain turn's
     *  is, so [outcome] hands it to whichever turn reads this round. Oct 4: a refusal reached [died] as an unnamed
     *  throwable, ended the round as an internal fault, and Claude Code retried a refused request as an overload for
     *  36 minutes. */
    private fun ended(ending: UpstreamEnding): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        source.fail(SOURCE_REFUSED)
        if (stoppedByHead) throw CancellationException(HEAD_STOPPED)
        localFailure?.let { return@synchronized it }
        // A record's next client step reads the source's outcome, as a torn source's does.
        sourceInterrupted = record != null
        record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, SOURCE_REFUSED) }
        this.ending = ending
        CodeModeEndings.placeholder()
    }

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = synchronized(lifecycle) {
        if (upstreamEnded || sourceLost) return@synchronized false
        if (localFailure != null || stoppedByHead) return@synchronized false
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
        // Deferred completion precedes died()'s registry write. Never continue before that cleanup settles.
        settled.await()
        // The reading turn, not the reader, is the one the ending ends: its slot is in this call's context.
        ending?.let { CodeModeEndings.settle(it) }
        val failure = awaited.exceptionOrNull() ?: return awaited.getOrThrow()
        if (failure is CodeModePersistenceException) throw failure
        return TurnOutcome.Failure(SOURCE_FAILED, cause = FailureCause.INTERNAL, phase = FailurePhase.MID_OUTPUT)
    }

    /** Disposal cancels the already-posted reader so its stream and admission slot are freed. */
    fun cancel() {
        if (upstreamEnded) return
        val reader = finished
        cut.cancel(reader, stoppedByHead, terminalSeen)
        executionLost = true
        source.fail(SOURCE_DISPOSED)
        reader?.cancel()
    }

    /** A cut can be consumed by one client step only, even after the record's execution lease was removed. */

    /** A cancelled first client step owns its cut, unless head replacement already ended this source. */
    fun stopClientStep() {
        synchronized(lifecycle) {
            if (!stoppedByHead) {
                if (!upstreamEnded) cut.cancel(finished, stoppedByHead = false, terminal = terminalSeen)
                headStopped = true
            }
        }
        cancel()
    }

    /** The synchronous registry stop owns persistence; a cancelled old reader cannot overwrite the next head. */
    fun stop() {
        synchronized(lifecycle) { headStopped = true }
        cancel()
    }

    /** Observer faults belong to splice. They never unwind through a transport's generic stream catch. */
    private fun observe(event: CustomToolSource) = synchronized(lifecycle) {
        if (event == CustomToolSource.Terminal) {
            terminalSeen = true
            return@synchronized
        }
        if (stoppedByHead) throw CancellationException(HEAD_STOPPED)
        if (sourceLost) return@synchronized
        if (localFailure != null || record?.terminal() == true) return@synchronized
        Cancellables.runCatchingBestEffort { capture.observe(event) }.onFailure(::reject)
    }

    private fun reject(error: Throwable): TurnOutcome.Failure {
        val rejected = CodeModeRejection.outcome(error)
        localFailure = rejected
        sourceInterrupted = false
        source.fail(rejected.message)
        try {
            localFailure = CodeModeRejection.lose(record, rejected, registry)
        } finally {
            ready.complete(null)
        }
        return checkNotNull(localFailure)
    }
}

// why: a disposed execution cannot retain its response reader or dispatch unread source.
internal const val SOURCE_DISPOSED = "code-mode execution disposed; source was not rerun"
private const val SOURCE_FAILED = "upstream source failed; source was not rerun"
private const val HEAD_STOPPED = "code-mode head stopped"
private const val SOURCE_REFUSED = "upstream refused the source request; source was not rerun"
private val SOURCE_TEAR_CAUSES = setOf(
    FailureCause.UPSTREAM_CONN_RESET,
    FailureCause.UPSTREAM_TRUNCATED,
    FailureCause.UPSTREAM_STALLED,
)

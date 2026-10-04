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
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.PostingTurnRow
import splice.upstream.TurnEnd
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamFailed
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
    private val settled = CompletableDeferred<Unit>()
    private val completion = CodeModeRoundCompletion(
        config.log,
        settled,
        CodeModeReaderDeath { cause ->
            try {
                died(cause)
            } finally {
                settle(readerEnd = true)
            }
        },
    )

    @Volatile var unexpectedDeath: Boolean = false
        private set

    private val lifecycle = Any()
    private var headStopped = false

    @Volatile private var upstreamEnded = false

    @Volatile var sourceInterrupted = false
        private set

    @Volatile var localFailure: TurnOutcome.Failure? = null
        private set

    /** The row of the client step that posted this round, owed its usage when the step returned first. Both
     *  guarded by [lifecycle], with [readerEnded]. */
    private var postingRow: PostingTurnRow? = null
    private var owed: CodeModeOwedRound? = null
    private var readerEnded = false

    fun start(scope: CoroutineScope, post: CodeModeRedirectablePost, body: CodeModeBody, end: TurnEnd) {
        check(finished == null)
        synchronized(lifecycle) { postingRow = post.postingRow }
        finished = scope.async(start = CoroutineStart.UNDISPATCHED) {
            switching.ownedBy(this)
            try {
                val outcome = post.into(body, switching)
                upstreamEnded = true
                val ended = finish(outcome)
                settle(readerEnd = false)
                ended
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
            } catch (error: UpstreamFailed) {
                refused(error)
            } finally {
                if (!ready.isCompleted) ready.complete(null)
            }
        }.also { reader ->
            reader.invokeOnCompletion { cause -> completion.completed(scope, cause, end) }
        }
    }

    /** The reader ended on a throwable none of [start]'s catches names (Oct 2: a ConcurrentModificationException
     *  out of the record's save). Its source had no terminal, so the cell reading it waited forever and nothing
     *  was logged. The source fails, the record is lost as [failed] loses it, and the throwable's class is named. */
    private fun died(cause: Throwable?) {
        val unnamed = cause?.takeUnless {
            it is CancellationException || it is CodeModePersistenceException || it is UpstreamFailed
        } ?: return
        upstreamEnded = true
        unexpectedDeath = true
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
        if (headStopped) throw CancellationException(HEAD_STOPPED)
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
        source.fail("splice code-mode source reader cancelled; source was not rerun")
        synchronized(lifecycle) {
            if (!headStopped) {
                record?.takeUnless(CodeModeRecord::terminal)
                    ?.let { registry.lose(it, "splice code-mode source reader cancelled; source was not rerun", error) }
            }
        }
    }

    private fun failed(error: Exception): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        if (headStopped) {
            source.fail(SOURCE_FAILED)
            throw CancellationException(HEAD_STOPPED, error)
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

    /** The upstream refused the source post and the retry loop gave up on it. That refusal is the client turn's
     *  ending, classified where a plain turn's is, so it leaves through [outcome] as itself. Oct 4: it reached
     *  [died] as an unnamed throwable, ended the round as an internal fault, and Claude Code retried a refused
     *  request as an overload for 36 minutes. */
    private fun refused(error: UpstreamFailed): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        source.fail(SOURCE_REFUSED)
        if (headStopped) throw CancellationException(HEAD_STOPPED, error)
        localFailure?.let { return@synchronized it }
        // A record's next client step reads the source's outcome, as a torn source's does.
        sourceInterrupted = record != null
        record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, SOURCE_REFUSED) }
        throw error
    }

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = synchronized(lifecycle) {
        if (upstreamEnded) return@synchronized false
        if (localFailure != null || headStopped) return@synchronized false
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
        val failure = awaited.exceptionOrNull() ?: return awaited.getOrThrow()
        if (failure is CodeModePersistenceException || failure is UpstreamFailed) throw failure
        return TurnOutcome.Failure(SOURCE_FAILED, cause = FailureCause.INTERNAL, phase = FailurePhase.MID_OUTPUT)
    }

    fun cancel() {
        if (!upstreamEnded) finished?.cancel()
    }

    /** The posting step's claim on this round's usage: the usage when the round has finished, or else the round
     *  is owed to the step's row, which [settle] releases once. Under [lifecycle], so the claim cannot fall
     *  between the terminal storing the usage and [settle] looking for a row to give it to. */
    fun claim(record: CodeModeRecord, step: TurnOutcome): Usage? = synchronized(lifecycle) {
        registry.source.consume(record) ?: run {
            if (step is TurnOutcome.Success && !readerEnded) {
                postingRow?.let { row -> owed = CodeModeOwedRound(row.hold(), step) }
            }
            null
        }
    }

    /** Releases the owed row, outside every lock: with the round's usage once its terminal stored it, or with
     *  none once the reader ended without it (a cut, a failure, a head stop). A claim that cannot be saved
     *  releases with none at the reader's end and leaves the round to the step that finishes its script. */
    private fun settle(readerEnd: Boolean) {
        val (due, usage) = synchronized(lifecycle) {
            if (readerEnd) readerEnded = true
            val due = owed ?: return
            val current = record ?: return
            val usage = try {
                registry.source.consume(current)
            } catch (error: CodeModePersistenceException) {
                config.log(
                    "[code-mode] source round billed later: its claim was not saved (${error::class.simpleName})",
                )
                null
            }
            if (usage == null && !readerEnd) return
            owed = null
            due to usage
        }
        Cancellables.runCatchingBestEffort { due.settle(usage) }.onFailure { failure ->
            config.log("[code-mode] the posting turn's row was not released (${failure::class.simpleName})")
        }
    }

    /** The synchronous registry stop owns persistence; a cancelled old reader cannot overwrite the next head. */
    fun stop() {
        synchronized(lifecycle) { headStopped = true }
        cancel()
    }

    /** Observer faults belong to splice. They never unwind through a transport's generic stream catch. */
    private fun observe(event: CustomToolSource) = synchronized(lifecycle) {
        if (headStopped) throw CancellationException(HEAD_STOPPED)
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
private const val HEAD_STOPPED = "code-mode head stopped"
private const val SOURCE_REFUSED = "upstream refused the source request; source was not rerun"
private val SOURCE_TEAR_CAUSES = setOf(
    FailureCause.UPSTREAM_CONN_RESET,
    FailureCause.UPSTREAM_TRUNCATED,
    FailureCause.UPSTREAM_STALLED,
)

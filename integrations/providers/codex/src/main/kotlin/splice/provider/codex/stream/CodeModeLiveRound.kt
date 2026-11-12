// NEW: a sealed exec item owns execution; the upstream reader separately owns its response terminal and billing.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import splice.core.perf.TurnPerf
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
import splice.provider.codex.stream.reader.CodeModeReaderFailures
import splice.provider.codex.stream.reader.CodeModeReaderOutcomes
import splice.provider.codex.stream.reader.CodeModeReaderSettlement
import splice.provider.codex.stream.reader.CodeModeRoundBilling
import splice.provider.codex.stream.reader.CodeModeRoundCompletion
import splice.provider.codex.stream.reader.CodeModeRoundLifecycle
import splice.provider.codex.stream.reader.CodeModeRoundRecord
import splice.provider.codex.stream.source.CodeModeSourceCapture
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
    headStop: HeadStopSignal? = null,
    recovery: CodeModeRecoveryHistory? = null,
) {
    /** Runs just before a parsed terminal settles. Production never sets it: billing tests hold that window. */
    internal var beforeSettle: Runnable?
        get() = settlement.beforeSettle
        set(value) { settlement.beforeSettle = value }
    private val capture = CodeModeSourceCapture(config, registry, wire, admission, recovery)
    val source = capture.source
    val ready = capture.ready
    suspend fun next(record: CodeModeRecord): CodeModeRecord? = capture.next(record)
    suspend fun select(record: CodeModeRecord): CodeModeRecord? = capture.select(record)
    fun executing(record: CodeModeRecord) = capture.executing(record)
    fun sourceFor(record: CodeModeRecord): CodeModeSourceBuffer = capture.sourceFor(record)
    fun deliveredNative(record: CodeModeRecord) = capture.deliveredNative(record)
    private val record: CodeModeRecord? get() = capture.record
    val activeRecord: CodeModeRecord? get() = capture.executingRecord
    val lifecycle = CodeModeRoundLifecycle(capture, headStop, registry, config.log)
    val billing = CodeModeRoundBilling(lifecycle, registry, CodeModeRoundRecord { record }, config.log)
    val switching = CodeModeSwitchingSink(sink, observer = CodeModeSourceObserver(::observe))
    val cut = CodeModeCutClaim()
    val key: String? get() = record?.key
    internal var finished: Deferred<TurnOutcome>? = null
        private set
    private val settled = CompletableDeferred<Unit>()
    private val readerOutcomes = CodeModeReaderOutcomes(config.log, capture, lifecycle, billing)
    private val settlement = CodeModeReaderSettlement(capture, lifecycle, readerOutcomes, billing, cut)

    /** The reader ended and its last billing settled: any row it was owed is released, and its usage is consumed or
     *  waits in the record's source state for the step that takes the outcome. */
    val readerSettled: Boolean get() = settled.isCompleted
    private val completion = CodeModeRoundCompletion(
        config.log,
        settled,
        settlement,
    )

    val unexpectedDeath: Boolean get() = readerOutcomes.unexpectedDeath

    private val stoppedByHead: Boolean get() = lifecycle.stoppedByHead

    internal var upstreamEnded: Boolean
        get() = readerOutcomes.upstreamEnded
        set(value) { readerOutcomes.upstreamEnded = value }

    /** The upstream delivered the round's terminal; its outcome has not reached [CodeModeRoundCompletion.settleRead]
     *  yet. A client step that fails in that gap cuts nothing: the source finished. */
    @Volatile private var terminalSeen = false
        private set

    val sourceInterrupted: Boolean get() = lifecycle.sourceInterrupted

    val localFailure: TurnOutcome.Failure? get() = lifecycle.localFailure

    /** The round lost the record and closed its cell without a transport tear: its terminal did not certify the
     *  admitted source, or its reader failed on splice's own non-IO fault. Only the step that was advancing that cell
     *  reads it; a later step continues on the client's history. */
    val sourceLost: Boolean get() = lifecycle.executionLost || capture.disposed

    /** The round closed a live cell under its client steps: [sourceLost], or a reader that died on an unnamed
     *  throwable. A step holding or acquiring that cell ends as a torn source's step does, never as invalid_request. */
    val closedLiveCell: Boolean get() = sourceLost || unexpectedDeath

    /** The permanent upstream failure that ended this round before its exec source completed. A client step whose cell
     *  that loss closed ends with it as it is, since no retry can change it. */
    val permanentEnding: TurnOutcome.Failure? get() = readerOutcomes.permanentEnding

    /** The perf of the turn that posted this source, which counts its upstream tries. */
    @Volatile var sourcePerf: TurnPerf? = null
        private set

    fun start(scope: CoroutineScope, post: CodeModeRedirectablePost, body: CodeModeBody, end: TurnEnd) {
        check(finished == null)
        billing.start(post.postingRow)
        sourcePerf = post.perf
        finished = scope.async(start = CoroutineStart.UNDISPATCHED) {
            switching.ownedBy(this)
            try {
                completion.settleRead(post, post.into(body, switching), switching)
            } catch (error: CancellationException) {
                readerOutcomes.cancelled(error)
                throw error
            } catch (error: IOException) {
                lifecycle.delivered(switching.confirmedText())
                readerOutcomes.failed(error)
            } catch (error: CodeModePersistenceException) {
                readerOutcomes.failed(error)
            } catch (error: IllegalStateException) {
                readerOutcomes.failed(error)
            } catch (error: IllegalArgumentException) {
                readerOutcomes.failed(error)
            } finally {
                capture.endAdmissions()
                if (!ready.isCompleted) ready.complete(null)
            }
        }.also { reader ->
            reader.invokeOnCompletion { cause -> completion.completed(scope, cause, end) }
        }
    }

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = synchronized(lifecycle) {
        if (upstreamEnded || sourceLost) return@synchronized false
        if (localFailure != null || stoppedByHead) return@synchronized false
        val current = capture.executingRecord ?: return@synchronized false
        if (current.sessionId != turn.sessionId || current.key != CodeModeTurnIdentity().turnKey(turn)) {
            return@synchronized false
        }
        val ids = registry.changes.clientIds(current)
        turn.toolResults.any { it.id in ids }
    }

    /** A reader that died on an unnamed throwable ends the round as a torn transport does, with the classified failure's
     *  outcome, never by throwing its throwable through the next client step. */
    suspend fun outcome(): TurnOutcome = completion.outcome(checkNotNull(finished))

    /** Disposal cancels the already-posted reader so its stream and admission slot are freed. */
    fun cancel() {
        if (upstreamEnded) return
        val reader = finished
        cut.cancel(reader, stoppedByHead, terminalSeen)
        lifecycle.executionLost = true
        source.dispose(CodeModeReaderFailures.SOURCE_DISPOSED)
        reader?.cancel()
    }

    /** A cut can be consumed by one client step only, even after the record's execution lease was removed. */

    /** A cancelled first client step owns its cut, unless head replacement already ended this source. */
    fun stopClientStep() {
        synchronized(lifecycle) {
            if (!stoppedByHead) {
                if (!upstreamEnded) cut.cancel(finished, stoppedByHead = false, terminal = terminalSeen)
                lifecycle.headStopped = true
            }
        }
        cancel()
    }

    /** The synchronous registry stop owns persistence; a cancelled old reader cannot overwrite the next head. */
    fun stop() {
        synchronized(lifecycle) { lifecycle.headStopped = true }
        cancel()
    }

    /** Observer faults belong to splice. They never unwind through a transport's generic stream catch. */
    private suspend fun observe(event: CustomToolSource) {
        val witness = if (event is CustomToolSource.Continuity) switching.confirmedText() else null
        synchronized(lifecycle) {
            if (event == CustomToolSource.Terminal) {
                terminalSeen = true
                return@synchronized
            }
            if (stoppedByHead) throw CancellationException(CodeModeReaderFailures.HEAD_STOPPED)
            if (sourceLost) return@synchronized
            if (localFailure != null) return@synchronized
            Cancellables.runCatchingBestEffort { capture.observe(event, witness) }.onFailure(lifecycle::reject)
        }
    }
}

// NEW: head stop, not the first client step, ends independently owned code generation.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeOutcomeAccumulator
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.sse.WireSink
import java.util.concurrent.ConcurrentHashMap

/** Source stops stay on the ending thread, after its outermost registry scope releases every lock. */
internal object CodeModeSourceEnds {
    private val pending = ThreadLocal<MutableList<CodeModeSourceLease>>()

    fun defer(lease: CodeModeSourceLease?) {
        if (lease == null) return
        val held = pending.get()
        if (held == null) lease.ended() else held.add(lease)
    }

    inline fun <T> unlocked(block: () -> T): T {
        if (pending.get() != null) return block()
        val leases = mutableListOf<CodeModeSourceLease>()
        pending.set(leases)
        return try {
            block()
        } finally {
            pending.remove()
            leases.forEach(CodeModeSourceLease::ended)
        }
    }
}

/** A retained record owns its reader until finalization, disposal or head stop. */
internal class CodeModeSourceLease(
    private val id: String,
    private val round: CodeModeLiveRound,
    private val rounds: ConcurrentHashMap<String, CodeModeLiveRound>,
) {
    fun ended() {
        rounds.remove(id, round)
        round.stop()
    }
}

internal class CodeModeStreams(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
) {
    private val scope = LifecycleScope(ProcessDispatchers().io())
    private val rounds = ConcurrentHashMap<String, CodeModeLiveRound>()
    private val reading: MutableSet<CodeModeLiveRound> = ConcurrentHashMap.newKeySet()

    fun begin(
        context: CodeModeRunContext,
        body: CodeModeBody,
        post: CodeModeRedirectablePost,
        admission: CodeModeStreamAdmission,
    ): CodeModeLiveRound = CodeModeLiveRound(config, registry, wire, admission, context.sink).also { round ->
        reading += round
        round.start(scope, post, body) { reading.remove(round) }
    }

    fun keep(record: CodeModeRecord, round: CodeModeLiveRound) {
        rounds[record.id] = round
        record.sourceEnd = CodeModeSourceLease(record.id, round, rounds)
    }

    fun find(record: CodeModeRecord): CodeModeLiveRound? = rounds[record.id]

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = rounds.values.any { it.owns(turn) }

    suspend fun attach(record: CodeModeRecord, sink: WireSink): WireSink {
        val round = rounds[record.id] ?: return sink
        round.switching.attach(sink)
        return CodeModeClientStepSink(round.switching, sink)
    }

    suspend fun takeOutcome(record: CodeModeRecord): TurnOutcome? {
        val round = rounds[record.id]
        val raw = try {
            round?.outcome()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (record.phase != splice.provider.codex.CodeModePhase.COMPLETED) throw error
            null
        }
        val usage = registry.source.consume(record)
        if (round != null) rounds.remove(record.id, round)
        record.sourceEnd = null
        round?.switching?.detach()
        return when (raw) {
            is TurnOutcome.Success -> raw.copy(usage = usage ?: Usage())
            null -> usage?.let { TurnOutcome.Success(false, false, it) }
            else -> raw
        }
    }

    /** A source round that finished before the client step that posted it ended is billed on that step, since its
     *  usage is in hand when the step's row is written. One still streaming then is billed by the step that finishes
     *  its script ([takeOutcome]); [CodeModeSourceRecords.consume] hands a round's usage out once either way. A claim
     *  that cannot be saved leaves the round to that later step rather than failing a step whose calls already left. */
    fun billFinished(record: CodeModeRecord, step: TurnOutcome): TurnOutcome {
        val usage = try {
            registry.source.consume(record)
        } catch (error: CodeModePersistenceException) {
            config.log("[code-mode] source round billed later: its claim was not saved (${error::class.simpleName})")
            null
        } ?: return step
        val accumulated = CodeModeOutcomeAccumulator()
        accumulated.absorb(TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = usage))
        return accumulated.finishLocal(step)
    }

    suspend fun endStep(record: CodeModeRecord) {
        val round = rounds[record.id] ?: return
        round.switching.detach()
        if (record.phase == splice.provider.codex.CodeModePhase.LOST) {
            rounds.remove(record.id, round)
            round.cancel()
        }
    }

    fun discard(record: CodeModeRecord) {
        record.sourceEnd?.ended()
        record.sourceEnd = null
    }

    fun stop() {
        reading.forEach(CodeModeLiveRound::stop)
        scope.coroutineContext.cancelChildren()
        rounds.clear()
    }
}

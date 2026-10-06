// NEW: drives bounded outer code-mode scripts and their hidden upstream continuation rounds.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRecordFactory
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeRuntimeStarter
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeStreamingCell
import splice.provider.codex.stream.CodeModeStreams
import splice.upstream.failure.CodeModeCapacityException
import java.io.IOException

private data class CodeModeDriveState(
    var body: CodeModeBody,
    var outcome: TurnOutcome,
    var suppliedOuter: GatewayCustomCall?,
)

internal class CodexCodeModeDriver(
    private val config: CodeModeBridgeConfig,
    private val run: CodeModeRuntimeRun,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val validation: CodexCodeModeValidation,
    private val machine: CodexCodeModeMachine,
) {
    val streams = CodeModeStreams(config, registry, wire)
    private val factory = CodeModeRecordFactory(config, wire)
    private val starter = CodeModeRuntimeStarter(run, registry, config)

    /** A redirectable post can yield a durable script while its upstream response remains live. */
    suspend fun post(context: CodeModeRunContext, initialOuter: GatewayCustomCall?, body: CodeModeBody): TurnOutcome {
        val post = context.post as? CodeModeRedirectablePost
            ?: return drive(context, initialOuter, body, context.post(body))
        val round = streams.begin(context, body, post) { call ->
            driveProblem(context, listOf(call), call)?.let { error(it) }
            validation.outer(call.copy(input = call.input.ifBlank { "source pending" }))?.let { error(it) }
            context.scripts++
            val record = checkNotNull(factory.create(context, call, body, TurnOutcome.Success(false, false, Usage())))
            record.sourceState = CodeModeSourceState()
            check(registry.add(record)) { "code-mode registry capacity reached" }
            record
        }
        var admitted: CodeModeRecord? = null
        try {
            val record = round.ready.await()
            admitted = record
            val outcome = readyOutcome(context, initialOuter, body, round, record)
            return round.localFailure ?: outcome
        } finally {
            val clientCancelled = !currentCoroutineContext().isActive
            withContext(NonCancellable) {
                val parked = admitted?.phase == CodeModePhase.ACTIVE || admitted?.phase == CodeModePhase.STARTING
                // A cancelled first client step must release even a reader blocked writing to that client.
                if (clientCancelled) round.stopClientStep() else if (!parked) round.cancel()
                try {
                    round.switching.detach()
                } finally {
                    admitted?.takeIf { it.phase == CodeModePhase.LOST }?.let(streams::discard)
                }
            }
        }
    }

    private suspend fun readyOutcome(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
        round: CodeModeLiveRound,
        record: CodeModeRecord?,
    ): TurnOutcome {
        round.localFailure?.let { return it }
        if (record == null) return drive(context, initialOuter, body, round.outcome())
        streams.keep(record, round)
        val (_, advanced) = startRuntime(record, context, round)
        return round.localFailure ?: if (record.phase == CodeModePhase.COMPLETED) {
            finishGenerated(record, context, body)
        } else {
            streams.billFinished(record, advanced)
        }
    }

    suspend fun finishGenerated(record: CodeModeRecord, context: CodeModeRunContext, body: CodeModeBody): TurnOutcome {
        val generated = streams.takeOutcome(record)
        context.recovery?.generated(generated)
        if (context.completed.none { it.id == record.id }) context.completed += record
        val posted = context.recovery?.upstream(context.completed) ?: context.completed
        val capture = posted.lastOrNull { it.id == record.id }
        val rewritten = context.post.canonicalize(body, posted, context.turn.toolMedia, capture)
        rewritten.error?.let { return failure(it) }
        val outcome = post(context, null, checkNotNull(rewritten.body))
        val accumulated = CodeModeOutcomeAccumulator()
        when (generated) {
            is TurnOutcome.Success -> accumulated.absorb(generated)
            is TurnOutcome.Failure -> accumulated.absorb(
                TurnOutcome.Success(false, false, (generated.partial?.usage ?: Usage()) + generated.salvagedUsage),
            )
            is TurnOutcome.ClientAbandoned -> accumulated.absorb(
                TurnOutcome.Success(false, false, generated.salvagedUsage),
            )
            null -> Unit
        }
        return accumulated.finish(outcome)
    }

    suspend fun drive(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        initialBody: CodeModeBody,
        initialOutcome: TurnOutcome,
    ): TurnOutcome {
        val accumulated = CodeModeOutcomeAccumulator()
        val state = CodeModeDriveState(initialBody, initialOutcome, initialOuter)
        var terminal: TurnOutcome? = null
        try {
            while (terminal == null) terminal = advanceDrive(context, state, accumulated)
        } catch (error: CodeModePersistenceException) {
            return accumulated.finishLocal(error.outcome())
        }
        return checkNotNull(terminal)
    }

    private suspend fun advanceDrive(
        context: CodeModeRunContext,
        state: CodeModeDriveState,
        accumulated: CodeModeOutcomeAccumulator,
    ): TurnOutcome? {
        val success = state.outcome as? TurnOutcome.Success ?: return accumulated.finish(state.outcome)
        val calls = success.customCalls
        val outer = state.suppliedOuter ?: calls.singleOrNull()
        val problem = driveProblem(context, calls, outer)
        return when {
            problem != null -> {
                accumulated.absorb(success)
                accumulated.finishLocal(failure(problem))
            }
            outer == null -> accumulated.finish(success)
            else -> advanceScript(context, state, accumulated, success, outer)
        }
    }

    private fun driveProblem(
        context: CodeModeRunContext,
        calls: List<GatewayCustomCall>,
        outer: GatewayCustomCall?,
    ): String? {
        val hasTooManyCalls = calls.size > 1
        val isMissingOuter = outer == null && calls.isNotEmpty()
        if (hasTooManyCalls || isMissingOuter) return "code mode accepts one outer custom call per round"
        if (outer == null) return null
        return when {
            context.completed.any { it.outerCallId == outer.callId } ->
                "duplicate completed code-mode call id '${outer.callId}'"
            config.maxRounds?.let { context.scripts >= it } == true -> "code-mode round limit exceeded"
            else -> null
        }
    }

    private suspend fun advanceScript(
        context: CodeModeRunContext,
        state: CodeModeDriveState,
        accumulated: CodeModeOutcomeAccumulator,
        success: TurnOutcome.Success,
        outer: GatewayCustomCall,
    ): TurnOutcome? {
        accumulated.absorb(success)
        context.scripts++
        val (record, advanced) = begin(context, outer, state.body, success)
        return if (record == null || record.phase != CodeModePhase.COMPLETED) {
            accumulated.finishLocal(advanced)
        } else {
            context.completed += record
            context.recovery?.generated(success)
            val posted = context.recovery?.upstream(context.completed) ?: context.completed
            val capture = posted.lastOrNull { it.id == record.id }
            val rewritten = context.post.canonicalize(state.body, posted, context.turn.toolMedia, capture)
            rewritten.error?.let { return accumulated.finishLocal(failure(it)) }
            state.body = checkNotNull(rewritten.body)
            accumulated.finish(post(context, null, state.body))
        }
    }

    private suspend fun begin(
        context: CodeModeRunContext,
        outer: GatewayCustomCall,
        body: CodeModeBody,
        outcome: TurnOutcome.Success,
    ): Pair<CodeModeRecord?, TurnOutcome> {
        validation.outer(outer)?.let { return null to failure(it) }
        val record = factory.create(context, outer, body, outcome)
            ?: return null to failure("code mode requires a Responses input array")
        return if (!registry.add(record)) {
            null to failure("code-mode registry capacity reached")
        } else {
            startRuntime(record, context)
        }
    }

    /** A proven failed boot retained unexecuted source, so an exact retry may start it. */
    suspend fun retryStart(record: CodeModeRecord, context: CodeModeRunContext, body: CodeModeBody): TurnOutcome {
        check(registry.add(record)) { "Code-mode record cannot restart" }
        val stream = streams.find(record)
        if (stream == null && record.sourceState?.complete == false) {
            registry.lose(record, "upstream source state was lost; source was not rerun")
            return failure(record.error.orEmpty())
        }
        val (_, advanced) = startRuntime(record, context, stream)
        stream?.switching?.detach()
        return if (record.phase == CodeModePhase.COMPLETED) finishGenerated(record, context, body) else advanced
    }

    private suspend fun attachmentFailure(record: CodeModeRecord, stream: CodeModeLiveRound?): TurnOutcome =
        if (stream?.localFailure != null) {
            checkNotNull(stream.localFailure)
        } else if (stream?.sourceInterrupted == true) {
            stream.outcome()
        } else {
            TurnOutcome.Failure(record.error.orEmpty(), cause = FailureCause.INTERNAL, phase = FailurePhase.MID_OUTPUT)
        }

    private suspend fun startRuntime(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound? = null,
    ): Pair<CodeModeRecord, TurnOutcome> = try {
        val started = starter.start(record, context, stream)
        val cell = if (stream == null) started else CodeModeStreamingCell(started, record, stream)
        if (!registry.attach(record, cell)) {
            record to attachmentFailure(record, stream)
        } else {
            record to machine.advance(
                record,
                context.turn,
                context.disableParallel,
                emptyList(),
                streams.attach(record, context.sink),
                stream,
                context.recovery?.delivery(record),
            )
        }
    } catch (error: CancellationException) {
        registry.lose(record, "code-mode runtime start cancelled; source was not rerun", error)
        throw error
    } catch (error: CodeModePersistenceException) {
        throw error
    } catch (error: CodeModeCapacityException) {
        // Every slot is busy with a presumed-live cell. Nothing ran: tell the MODEL, in the script's
        // own output, and let the turn continue — a 502 here retried identically until new user
        // content arrived (2026-09-07, 87 failed turns on one head).
        val detail = "$CAPACITY_DETAIL: ${error.message}"
        config.log(
            "[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${record.outerCallId}): $detail",
        )
        record to machine.interrupt(record, detail)
    } catch (error: IOException) {
        record to failedStart(record, stream, error)
    } catch (_: IllegalArgumentException) {
        registry.lose(record, "code-mode runtime rejected its input; source was not rerun")
        record to failure(record.error.orEmpty())
    } catch (_: RuntimeException) {
        record to starter.failed(record, null)
    }

    private suspend fun failedStart(
        record: CodeModeRecord,
        stream: CodeModeLiveRound?,
        error: IOException,
    ): TurnOutcome =
        if (stream?.sourceInterrupted == true) stream.outcome() else starter.failed(record, error)

    private fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(
            message,
            deterministic = true,
            // V4-117: every path through this helper is the code-mode machine reporting its own
            // protocol state, so they share ONE cause rather than each borrowing an upstream one.
            // The type PARAMETER this used to take is gone with the hand-picked type itself: the
            // callers were choosing between INVALID_REQUEST and API_ERROR for the same cause, which
            // is exactly the second author the derived type removes.
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
        )
}

internal const val CODE_MODE_RECORD_LOG_CHARS: Int = 8

// V4-388: exec is the only tool a code_mode_only turn declares, so the detail cannot send the model to
// direct calls; a running cell frees its worker when it finishes.
private const val CAPACITY_DETAIL: String =
    "code-mode worker capacity reached; nothing was executed. Call exec again once a running script finishes"

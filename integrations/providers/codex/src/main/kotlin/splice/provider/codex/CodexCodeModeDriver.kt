// NEW: drives bounded outer code-mode scripts and their hidden upstream continuation rounds.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRecordFactory
import splice.provider.codex.stream.CodeModeRuntimeStarter
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeStreamingCell
import splice.provider.codex.stream.CodeModeStreams
import splice.upstream.RedirectableRoundPost
import splice.upstream.failure.CodeModeCapacityException
import java.io.IOException

private data class CodeModeDriveState(
    var bodyJson: String,
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
    private val factory = CodeModeRecordFactory(config)
    private val starter = CodeModeRuntimeStarter(run, registry, config)

    /** A redirectable post can yield a durable script while its upstream response remains live. */
    suspend fun post(context: CodeModeRunContext, initialOuter: GatewayCustomCall?, body: String): TurnOutcome {
        val post = context.post as? RedirectableRoundPost
            ?: return drive(context, initialOuter, body, context.post(body))
        val round = streams.begin(context, body, post) { call ->
            driveProblem(context, listOf(call), call)?.let { error(it) }
            validation.outer(call.copy(input = call.input.ifBlank { "source pending" }))?.let { error(it) }
            context.scripts++
            val boundary = checkNotNull(wire.anchoredBoundary(body, context.completed))
            val record = factory.create(
                context,
                call,
                boundary,
                wire.continuity(TurnOutcome.Success(false, false, Usage())),
            )
            record.sourceState = CodeModeSourceState()
            check(registry.add(record)) { "code-mode registry capacity reached" }
            record
        }
        var admitted: CodeModeRecord? = null
        try {
            val record = round.ready.await()
            admitted = record
            val outcome = if (record == null) {
                drive(context, initialOuter, body, round.outcome())
            } else {
                streams.keep(record, round)
                val (_, advanced) = startRuntime(record, context, round)
                if (record.phase == CodeModePhase.COMPLETED) finishGenerated(record, context, body) else advanced
            }
            return outcome
        } finally {
            round.switching.detach()
            val parked = admitted?.phase == CodeModePhase.ACTIVE || admitted?.phase == CodeModePhase.STARTING
            if (!parked) round.cancel()
            admitted?.takeIf { it.phase == CodeModePhase.LOST }?.let(streams::discard)
        }
    }

    suspend fun finishGenerated(record: CodeModeRecord, context: CodeModeRunContext, body: String): TurnOutcome {
        val generated = streams.takeOutcome(record)
        if (context.completed.none { it.id == record.id }) context.completed += record
        val rewritten = wire.canonicalize(body, context.completed, context.turn.toolMedia)
        rewritten.error?.let { return failure(it) }
        val outcome = post(context, null, checkNotNull(rewritten.bodyJson))
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
        initialBody: String,
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
        val (record, advanced) = begin(context, outer, state.bodyJson, success)
        return if (record == null || record.phase != CodeModePhase.COMPLETED) {
            accumulated.finishLocal(advanced)
        } else {
            context.completed += record
            val rewritten = wire.canonicalize(state.bodyJson, context.completed, context.turn.toolMedia)
            rewritten.error?.let { return accumulated.finishLocal(failure(it)) }
            state.bodyJson = checkNotNull(rewritten.bodyJson)
            accumulated.finish(post(context, null, state.bodyJson))
        }
    }

    private suspend fun begin(
        context: CodeModeRunContext,
        outer: GatewayCustomCall,
        bodyJson: String,
        outcome: TurnOutcome.Success,
    ): Pair<CodeModeRecord?, TurnOutcome> {
        validation.outer(outer)?.let { return null to failure(it) }
        val boundary = wire.anchoredBoundary(bodyJson, context.completed)
            ?: return null to failure("code mode requires a Responses input array")
        val continuity = wire.continuity(outcome)
        val record = factory.create(context, outer, boundary, continuity)
        return if (!registry.add(record)) {
            null to failure("code-mode registry capacity reached")
        } else {
            startRuntime(record, context)
        }
    }

    /** A proven failed boot retained unexecuted source, so an exact retry may start it. */
    suspend fun retryStart(record: CodeModeRecord, context: CodeModeRunContext, bodyJson: String): TurnOutcome {
        check(registry.add(record)) { "Code-mode record cannot restart" }
        val stream = streams.find(record)
        if (stream == null && record.sourceState?.complete == false) {
            registry.lose(record, "upstream source state was lost; source was not rerun")
            return failure(record.error.orEmpty())
        }
        val (_, advanced) = startRuntime(record, context, stream)
        stream?.switching?.detach()
        return if (record.phase == CodeModePhase.COMPLETED) finishGenerated(record, context, bodyJson) else advanced
    }

    private suspend fun startRuntime(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound? = null,
    ): Pair<CodeModeRecord, TurnOutcome> = try {
        val started = starter.start(record, context, stream)
        val cell = if (stream == null) started else CodeModeStreamingCell(started, record, stream)
        if (!registry.attach(record, cell)) {
            record to TurnOutcome.Failure(
                record.error.orEmpty(),
                cause = FailureCause.INTERNAL,
                phase = FailurePhase.MID_OUTPUT,
            )
        } else {
            record to machine.advance(
                record,
                context.turn,
                context.disableParallel,
                emptyList(),
                streams.attach(record, context.sink),
            )
        }
    } catch (error: CancellationException) {
        registry.lose(record, "code-mode runtime start cancelled; source was not rerun", error)
        throw error
    } catch (error: CodeModePersistenceException) {
        throw error
    } catch (_: CodeModeCapacityException) {
        // Every slot is busy with a presumed-live cell. Nothing ran: tell the MODEL, in the script's
        // own output, and let the turn continue — a 502 here retried identically until new user
        // content arrived (2026-09-07, 87 failed turns on one head).
        config.log(
            "[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${record.outerCallId}): $CAPACITY_DETAIL",
        )
        record to machine.interrupt(record, CAPACITY_DETAIL)
    } catch (error: IOException) {
        record to starter.failed(record, error)
    } catch (_: IllegalArgumentException) {
        registry.lose(record, "code-mode runtime rejected its input; source was not rerun")
        record to failure(record.error.orEmpty())
    } catch (_: RuntimeException) {
        record to starter.failed(record, null)
    }

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

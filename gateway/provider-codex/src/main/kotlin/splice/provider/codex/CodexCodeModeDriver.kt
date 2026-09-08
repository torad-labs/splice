// NEW: drives bounded outer code-mode scripts and their hidden upstream continuation rounds.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeCapacityException
import splice.spi.CodeModeInfrastructureException
import splice.spi.CodeModeTimeoutException
import java.io.IOException
import java.util.UUID

private data class CodeModeDriveState(
    var bodyJson: String,
    var outcome: TurnOutcome,
    var suppliedOuter: GatewayCustomCall?,
    var scripts: Int = 0,
)

internal class CodexCodeModeDriver(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val validation: CodexCodeModeValidation,
    private val machine: CodexCodeModeMachine,
) {
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
        val problem = driveProblem(context, state, calls, outer)
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
        state: CodeModeDriveState,
        calls: List<GatewayCustomCall>,
        outer: GatewayCustomCall?,
    ): String? {
        val hasTooManyCalls = calls.size > 1
        val isMissingOuter = outer == null && calls.isNotEmpty()
        if (hasTooManyCalls || isMissingOuter) return "code mode accepts one outer custom call per round"
        if (outer == null) return null
        return when {
            registry.completed(context.key).any { it.outerCallId == outer.callId } ->
                "duplicate completed code-mode call id '${outer.callId}'"
            state.scripts >= config.maxRounds -> "code-mode round limit exceeded"
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
        state.scripts++
        val (record, advanced) = begin(context, outer, state.bodyJson, success)
        return if (record == null || record.phase != CodeModePhase.COMPLETED) {
            accumulated.finishLocal(advanced)
        } else {
            val rewritten = wire.canonicalize(state.bodyJson, registry.completed(context.key))
            rewritten.error?.let { return accumulated.finishLocal(failure(it)) }
            state.bodyJson = checkNotNull(rewritten.bodyJson)
            state.outcome = context.post(state.bodyJson)
            state.suppliedOuter = null
            null
        }
    }

    private suspend fun begin(
        context: CodeModeRunContext,
        outer: GatewayCustomCall,
        bodyJson: String,
        outcome: TurnOutcome.Success,
    ): Pair<CodeModeRecord?, TurnOutcome> {
        validation.outer(outer)?.let { return null to failure(it) }
        val boundary = wire.inputBoundary(bodyJson)
            ?: return null to failure("code mode requires a Responses input array")
        val continuity = wire.continuity(outcome)
        val record = CodeModeRecord(
            id = UUID.randomUUID().toString(),
            key = context.key,
            outer = outer.raw,
            outerCallId = outer.callId,
            source = outer.input,
            phase = CodeModePhase.LOST,
            updatedAt = config.clock.millis(),
            lastDigest = context.digest,
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = boundary.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = boundary.logicalDigest,
            nativeSegments = boundary.nativeSegments,
            continuity = continuity.logicalItems,
            continuityReplay = continuity.replayItems,
        )
        return if (!registry.add(record)) {
            null to failure("code-mode registry capacity reached", ErrorType.API_ERROR)
        } else {
            startRuntime(record, context)
        }
    }

    private suspend fun startRuntime(
        record: CodeModeRecord,
        context: CodeModeRunContext,
    ): Pair<CodeModeRecord, TurnOutcome> = try {
        val cell = startWithEviction(record, context)
        if (!registry.attach(record, cell)) {
            record to failure(record.error.orEmpty(), ErrorType.API_ERROR)
        } else {
            record to machine.advance(
                record,
                context.turn,
                context.disableParallel,
                emptyList(),
                context.sink,
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
            "[code-mode] ${record.id.take(RECORD_ID_LOG_CHARS)} (outer ${record.outerCallId}): $CAPACITY_DETAIL",
        )
        record to machine.interrupt(record, CAPACITY_DETAIL)
    } catch (_: CodeModeTimeoutException) {
        registry.lose(record, "code-mode runtime timed out during startup; source was not rerun")
        record to failure(record.error.orEmpty(), ErrorType.API_ERROR)
    } catch (error: CodeModeInfrastructureException) {
        registry.lose(
            record,
            "code-mode infrastructure failure ${error.category}/${error.faultClass}; source was not rerun",
        )
        record to failure(record.error.orEmpty(), ErrorType.API_ERROR)
    } catch (error: IOException) {
        record to startFailure(record, error)
    } catch (_: RuntimeException) {
        record to startFailure(record, null)
    }

    /** One start attempt; at capacity the oldest parked cell is evicted first and the start retried once. */
    private suspend fun startWithEviction(record: CodeModeRecord, context: CodeModeRunContext) = try {
        config.runtime.start(record.source, context.turn.tools)
    } catch (error: CodeModeCapacityException) {
        registry.evictIdleCell() ?: throw error
        config.runtime.start(record.source, context.turn.tools)
    }

    /** The spawn failure's cause chain goes to the head log; the previous `catch (_: …)` hid it, and
     *  an hour of "runtime failed to start" carried no clue that the pool was simply full. */
    private fun startFailure(record: CodeModeRecord, error: Exception?): TurnOutcome.Failure {
        val chain = generateSequence<Throwable>(error) { it.cause }
            .joinToString(": ") { it.message ?: it::class.simpleName.orEmpty() }
            .ifEmpty { "runtime exception" }
        config.log(
            "[code-mode] ${record.id.take(RECORD_ID_LOG_CHARS)} (outer ${record.outerCallId}): " +
                "runtime failed to start — $chain",
        )
        registry.lose(record, "code-mode runtime failed to start; source was not rerun")
        return failure(record.error.orEmpty(), ErrorType.API_ERROR)
    }

    private fun failure(message: String, type: ErrorType = ErrorType.INVALID_REQUEST): TurnOutcome.Failure =
        TurnOutcome.Failure(type, message, deterministic = true)
}

private const val RECORD_ID_LOG_CHARS: Int = 8
private const val CAPACITY_DETAIL: String =
    "code-mode worker capacity reached; nothing was executed — call the tools directly this turn"

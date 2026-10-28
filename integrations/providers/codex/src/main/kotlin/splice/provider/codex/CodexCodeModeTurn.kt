// NEW: serializes each conversation's code-mode replay, resume, and fresh-turn decisions.
package splice.provider.codex

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import splice.core.perf.PerfKeys
import splice.core.turn.GatewayCustomCall
import splice.core.turn.HeadStopKey
import splice.core.turn.TurnOutcome
import splice.provider.codex.availability.CodeModeAvailabilityPolicy
import splice.provider.codex.branch.CodeModeOwnerPlacement
import splice.provider.codex.branch.CodeModePlacedOwner
import splice.provider.codex.branch.CodexCodeModeBranch
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.provider.codex.state.CodeModeTurnLocks
import splice.provider.codex.state.CodeModeTurnNotes
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.provider.codex.stream.CodeModeRejection
import splice.provider.codex.stream.CodeModeRunInput
import splice.provider.codex.stream.batch.CodeModeBatchHistory
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.ProcessDispatchers

/**
 * History that no longer lines up with a record is DEGRADED, never refused. The status quo before
 * code mode was "send the history the client sent", and the client's history is always a valid one:
 * the owned callbacks are real tool calls with real outputs. A refusal instead turns any false
 * positive (a grown tool list, a model switch, a record past its TTL) into a dead conversation with
 * no way out but compaction — which is what happened live on 2026-09-07.
 */
internal class CodexCodeModeTurn(
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val driver: CodexCodeModeDriver,
    private val resume: CodexCodeModeResume,
    private val machine: CodexCodeModeMachine,
    private val validation: CodexCodeModeValidation,
    config: CodeModeBridgeConfig,
) {
    private val log = config.log
    val availability = CodeModeAvailabilityPolicy(config)
    private val identity = CodeModeTurnIdentity()
    private val notes = CodeModeTurnNotes(registry, log)
    private val branch = CodexCodeModeBranch(registry, driver, machine, validation, log)
    private val locks = CodeModeTurnLocks()

    private val placement = CodeModeOwnerPlacement(registry, wire, machine, log)

    suspend fun run(input: CodeModeRunInput): TurnOutcome {
        val key = identity.turnKey(input.turn)
        val branchKey = identity.branchKey(key, input.body.request?.second)
        val held = input.post.perf?.timed(PerfKeys.CODE_MODE_TURN_LOCK_WAIT_MS) { locks.acquire(branchKey, key) }
            ?: locks.acquire(branchKey, key)
        return try {
            runHeld(input, key)
        } finally {
            locks.release(branchKey, key, held)
        }
    }

    private suspend fun resolveTurn(input: CodeModeRunInput, key: String): CodexCodeModeBridge.Turn {
        val original = input.turn
        if (original.toolResults.isEmpty()) return original
        val ids = original.toolResults.mapTo(HashSet()) { it.id }
        val before = registry.mcpOwners.snapshot(key, original.sessionId, ids)
        val captured = runInterruptible(ProcessDispatchers().io()) { validation.resolveCallbacks(original, before) }
        val after = registry.mcpOwners.snapshot(key, original.sessionId, ids)
        return validation.reconcileCallbacks(original, captured, before, after)
    }

    private suspend fun runHeld(input: CodeModeRunInput, key: String): TurnOutcome {
        val context = CodeModeRunContext(
            resolveTurn(input, key),
            input.disableParallel,
            key,
            identity.digest(input.body.round),
            CodeModeRoundLink(input.sink, input.post, input.body),
            currentCoroutineContext()[HeadStopKey],
            input.recovery,
        )
        availability.announce(context)
        val watched = driver.streams.watchCuts(key)
        val notificationOwner = registry.notifications.open(context, driver.sent.notifications)
        return try {
            val outcome = try {
                // An identical request is served before retention can trim its recorded step.
                branch.replay(context) ?: started(context, input)
            } catch (error: CodeModePersistenceException) {
                error.outcome()
            }
            val billed = context.abandonedSources.fold(outcome) { step, record ->
                driver.streams.billFinished(record, step, watched[record.id])
            }
            // The one exit every client-facing step takes, so none tells Claude Code its context is zero.
            registry.contexts.report(key, driver.streams.billCuts(watched, context.postedSources, billed))
        } finally {
            registry.notifications.close(notificationOwner)
            // A refusal or cancellation has no returned outcome, but the cutting turn still owns its perf row.
            val cuts = driver.streams.takeCuts(watched, context.postedSources)
            input.post.perf?.add(PerfKeys.CUT_SOURCE_ROUNDS, cuts)
        }
    }

    /** A new step of the conversation. A sibling branch's script is measured on the records a trim would let go,
     *  so the trim waits until no sibling drive is queued or running. */
    private suspend fun started(context: CodeModeRunContext, input: CodeModeRunInput): TurnOutcome {
        if (locks.alone(context.key)) registry.turnStart.begin(context.key)
        return runLocked(context, input.initialOuter, input.body)
    }

    private suspend fun runLocked(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
    ): TurnOutcome {
        notes.announce(context)
        val records = registry.recordsFor(context.key)
        context.turn.rendering.verifiedCleared.clear()
        driver.sent.acknowledgeCleared(context, records)
        val conflicts = branch.conflictingRecords(context)
        context.conflicts = conflicts
        context.completed += registry.completed(context.key).filterNot { it.id in conflicts }
        driver.sent.begin(context, records)
        val extended = driver.sent.extend(context, records)
        return extended?.let { extended(context, initialOuter, it) } ?: ordinary(context, initialOuter, body)
    }

    private suspend fun ordinary(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
    ): TurnOutcome {
        val completedHistory = branch.canonicalize(context, body, wire.callbackIds(body))
        completedHistory.error?.let { return CodeModeRejection.state(it) }
        val canonicalBody = checkNotNull(completedHistory.body)
        reconcile(context, canonicalBody)
        return decide(context, initialOuter, canonicalBody, placement.find(context, canonicalBody))
    }

    /** A request built from the conversation's kept posts, or from its whole history once when it extends none:
     *  nothing sent is rewritten, so a running script is found by its call ids and resumes on this history, never
     *  placed again or abandoned. */
    private suspend fun extended(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
    ): TurnOutcome {
        reconcile(context, body)
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val owner = registry.owner(context.key, context.digest, resultIds, wire.callbackIds(body), context.conflicts)
        return decide(context, initialOuter, body, owner?.let { CodeModePlacedOwner(it, body) })
    }

    /** What [body] is sent as: a script's generated reply, a running script resumed, or a post. A fork's history is
     *  sent as the divergence it is, and kept like any other post. */
    private suspend fun decide(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
        owner: CodeModePlacedOwner?,
    ): TurnOutcome {
        val completed = context.completed
        val terminal = completed.lastOrNull(CodeModeBatchHistory::unclaimed)
        return when {
            terminal != null -> driver.finishGenerated(terminal, context, body)
            owner != null -> resumeOwner(owner, context)
            context.conflicts.isNotEmpty() -> {
                driver.sent.sent(context, body)
                driver.sent.settled(context, branch.sendOwnHistory(context, body))
            }
            completed.any { it.progress.lastDigest == context.digest } -> driver.post(context, null, body)
            else -> driver.post(context, initialOuter, body)
        }
    }

    /** Missing callbacks prove supersession only on a history that still extends the parked baseline.
     * Divergent or shorter side requests do not decide whether the original client can return. */
    private fun reconcile(context: CodeModeRunContext, body: CodeModeBody) {
        val callbacks = wire.callbackIds(body) + context.turn.toolResults.map(CodeModeResult::id)
        val continued = registry.recordsFor(context.key).filter { it.phase == CodeModePhase.ACTIVE }
            .filter { record ->
                record.clientIds().any { it in callbacks } || wire.restoreBaseline(body, record).error != null
            }.mapTo(mutableSetOf()) { it.id }
        registry.turnStart.begin(context.key, context.digest, continued)
    }

    private suspend fun resumeOwner(placed: CodeModePlacedOwner, context: CodeModeRunContext): TurnOutcome =
        when (placed.record.phase) {
            CodeModePhase.STARTING -> driver.retryStart(placed.record, context, placed.body)
            CodeModePhase.ACTIVE -> resume.active(placed.record, context, placed.body)
            CodeModePhase.LOST -> resume.lost(placed.record, context, placed.body)
            CodeModePhase.COMPLETED -> error("A completed record cannot own a pending turn")
        }

    internal fun abandon(owner: CodeModeRecord, error: String, rejection: CodeModeNativeRejection?) =
        placement.abandon(owner, error, rejection)
}

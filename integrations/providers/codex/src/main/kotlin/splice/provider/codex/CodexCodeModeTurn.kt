// NEW: serializes each conversation's code-mode replay, resume, and fresh-turn decisions.
package splice.provider.codex

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.provider.codex.branch.CodexCodeModeBranch
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.provider.codex.state.CodeModeTurnLocks
import splice.provider.codex.state.CodeModeTurnNotes
import splice.upstream.InterceptedRoundPost
import splice.upstream.codemode.CodeModeResult
import splice.upstream.sse.WireSink

internal data class CodeModeRunInput(
    val turn: CodexCodeModeBridge.Turn,
    val initialOuter: GatewayCustomCall?,
    val disableParallel: Boolean,
    val bodyJson: String,
    val sink: WireSink,
    val post: InterceptedRoundPost,
)

internal data class CodeModeRunContext(
    val turn: CodexCodeModeBridge.Turn,
    val disableParallel: Boolean,
    val key: String,
    val digest: String,
    val sink: WireSink,
    val post: InterceptedRoundPost,
) {
    /** One filtered history for this request, extended only by its own completed scripts. */
    val completed: MutableList<CodeModeRecord> = mutableListOf()
    var scripts: Int = 0
}

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
    private val log: LogSink,
) {
    private val identity = CodeModeTurnIdentity()
    private val notes = CodeModeTurnNotes(registry, log)
    private val branch = CodexCodeModeBranch(registry, driver, machine, validation, log)
    private val locks = CodeModeTurnLocks()

    private data class PlacedOwner(val record: CodeModeRecord, val bodyJson: String)

    suspend fun run(input: CodeModeRunInput): TurnOutcome {
        val key = identity.turnKey(input.turn)
        val context = CodeModeRunContext(
            input.turn,
            input.disableParallel,
            key,
            identity.digest(input.bodyJson),
            input.sink,
            input.post,
        )
        val held = locks.acquire(key)
        return try {
            try {
                // An identical request is served before retention can trim its recorded step.
                val replay = branch.replay(context)
                if (replay != null) {
                    replay
                } else {
                    registry.turnStart.begin(context.key)
                    runLocked(context, input.initialOuter, input.bodyJson)
                }
            } catch (error: CodeModePersistenceException) {
                error.outcome()
            }
        } finally {
            locks.release(key, held)
        }
    }

    private suspend fun runLocked(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        bodyJson: String,
    ): TurnOutcome {
        notes.announce(context)
        return ordinary(context, initialOuter, bodyJson, branch.conflictingRecords(context))
    }

    private suspend fun ordinary(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        bodyJson: String,
        conflicts: Set<String>,
    ): TurnOutcome {
        val completed = context.completed
        completed += registry.completed(context.key).filterNot { it.id in conflicts }
        val completedHistory = wire.canonicalize(bodyJson, completed, context.turn.toolMedia)
        completedHistory.error?.let { return failure(it) }
        val canonicalBody = checkNotNull(completedHistory.bodyJson)
        reconcile(context, canonicalBody)
        val owner = placedOwner(context, canonicalBody, conflicts)
        val terminal = completed.lastOrNull { it.sourceState?.usage != null && it.sourceState?.consumed == false }
        return when {
            terminal != null -> driver.finishGenerated(terminal, context, canonicalBody)
            owner != null -> resumeOwner(owner, context)
            conflicts.isNotEmpty() -> branch.sendOwnHistory(context, canonicalBody)
            completed.any { it.lastDigest == context.digest } ->
                driver.post(context, null, canonicalBody)
            else -> driver.post(context, initialOuter, canonicalBody)
        }
    }

    /** Missing callbacks prove supersession only on a history that still extends the parked baseline.
     * Divergent or shorter side requests do not decide whether the original client can return. */
    private fun reconcile(context: CodeModeRunContext, bodyJson: String) {
        val callbacks = wire.callbackIds(bodyJson) + context.turn.toolResults.map(CodeModeResult::id)
        val continued = registry.recordsFor(context.key).filter { it.phase == CodeModePhase.ACTIVE }
            .filter { record ->
                record.clientIds().any { it in callbacks } || wire.restoreBaseline(bodyJson, record).error != null
            }.mapTo(mutableSetOf()) { it.id }
        registry.turnStart.begin(context.key, context.digest, continued)
    }

    /** The active or lost owner whose baseline still places in this history, with that history
     *  restored around it — or null when there is none, or when the one there was is abandoned. */
    private fun placedOwner(context: CodeModeRunContext, canonicalBody: String, conflicts: Set<String>): PlacedOwner? {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val callbackIds = wire.callbackIds(canonicalBody)
        val owner = registry.owner(context.key, context.digest, resultIds, callbackIds, conflicts) ?: return null
        val restored = wire.restoreBaseline(canonicalBody, owner)
        val error = restored.error ?: return PlacedOwner(owner, checkNotNull(restored.bodyJson))
        abandon(owner, error)
        return null
    }

    private suspend fun resumeOwner(placed: PlacedOwner, context: CodeModeRunContext): TurnOutcome =
        when (placed.record.phase) {
            CodeModePhase.STARTING -> driver.retryStart(placed.record, context, placed.bodyJson)
            CodeModePhase.ACTIVE -> resume.active(placed.record, context, placed.bodyJson)
            CodeModePhase.LOST -> resume.lost(placed.record, context, placed.bodyJson)
            CodeModePhase.COMPLETED -> error("A completed record cannot own a pending turn")
        }

    /** A running script whose history moved underneath it is abandoned: cell closed, evidence kept
     *  on the LOST record, and the turn continues upstream on the client's own history. */
    private fun abandon(owner: CodeModeRecord, error: String) {
        val detail = "$CODE_MODE_ABANDONED: $error; source was not rerun"
        registry.lose(owner, detail)
        // History only grows, so a record that no longer places never will again: it retires with its
        // evidence now. Left LOST it was found by its client ids and abandoned again on every later
        // turn of the conversation (82 identical lines for one record on 2026-09-20).
        machine.interrupt(owner, detail)
        log(
            "[code-mode] abandoned record ${owner.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${owner.outerCallId}): " +
                "$error; continuing upstream on the client's history",
        )
    }

    private fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(
            message,
            deterministic = true,
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
        )
}

// NEW: serializes each conversation's code-mode replay, resume, and fresh-turn decisions.
package splice.provider.codex

import kotlinx.coroutines.currentCoroutineContext
import splice.core.perf.PerfKeys
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.FailureTraits
import splice.core.turn.GatewayCustomCall
import splice.core.turn.HeadStopKey
import splice.core.turn.HeadStopSignal
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.LogSink
import splice.provider.codex.branch.CodeModeOwnerPlacement
import splice.provider.codex.branch.CodeModePlacedOwner
import splice.provider.codex.branch.CodexCodeModeBranch
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.provider.codex.state.CodeModeTurnLocks
import splice.provider.codex.state.CodeModeTurnNotes
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRecoveryHistory
import splice.provider.codex.stream.CodeModeRunInput
import splice.provider.codex.stream.CodeModeUpstreamPost
import splice.provider.codex.stream.batch.CodeModeBatchHistory
import splice.upstream.codemode.CodeModeResult
import splice.upstream.sse.WireSink
internal data class CodeModeRunContext(
    val turn: CodexCodeModeBridge.Turn,
    val disableParallel: Boolean,
    val key: String,
    val digest: String,
    val link: CodeModeRoundLink,
    val headStop: HeadStopSignal? = null,
    val recovery: CodeModeRecoveryHistory? = null,
) {
    /** The request as the client sent it, which every post of this step extends the last post with. */
    val client: CodeModeBody? get() = link.client

    /** One filtered history for this request, extended only by its own completed scripts. */
    val completed: MutableList<CodeModeRecord> = java.util.concurrent.CopyOnWriteArrayList()

    /** Sources posted by this step can be cut before it ever parks or holds a billing row. */
    val postedSources: MutableList<CodeModeLiveRound> = mutableListOf()

    /** Placement rejection removes execution, not the abandoning step's ownership of its source bill. */
    val abandonedSources: MutableList<CodeModeRecord> = mutableListOf()
    var scripts: Int = 0

    /** Records whose accepted result this request changed (a fork): their calls stay the client's. */
    var conflicts: Set<String> = emptySet()
}

/** The client-facing [sink] a round streams to, the [post] that sends its request upstream, and the request the
 *  [client] sent, which a copy of the round keeps. */
internal data class CodeModeRoundLink(
    val sink: WireSink,
    val post: CodeModeUpstreamPost,
    var client: CodeModeBody? = null,
)

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

    private val placement = CodeModeOwnerPlacement(registry, wire, machine, log)

    suspend fun run(input: CodeModeRunInput): TurnOutcome {
        val key = identity.turnKey(input.turn)
        val context = CodeModeRunContext(
            input.turn,
            input.disableParallel,
            key,
            identity.digest(input.body.round),
            CodeModeRoundLink(input.sink, input.post, input.body),
            currentCoroutineContext()[HeadStopKey],
            input.recovery,
        )
        val held = input.post.perf?.timed(PerfKeys.CODE_MODE_TURN_LOCK_WAIT_MS) { locks.acquire(key) }
            ?: locks.acquire(key)
        val watched = driver.streams.watchCuts(key)
        return try {
            val outcome = try {
                // An identical request is served before retention can trim its recorded step.
                val replay = branch.replay(context)
                if (replay != null) {
                    replay
                } else {
                    registry.turnStart.begin(context.key)
                    runLocked(context, input.initialOuter, input.body)
                }
            } catch (error: CodeModePersistenceException) {
                error.outcome()
            }
            val billed = context.abandonedSources.fold(outcome) { step, record ->
                driver.streams.billFinished(record, step, watched[record.id])
            }
            // The one exit every client-facing step takes, so none tells Claude Code its context is zero.
            registry.contexts.report(key, driver.streams.billCuts(watched, context.postedSources, billed))
        } finally {
            // A refusal or cancellation has no returned outcome, but the cutting turn still owns its perf row.
            try {
                val cuts = driver.streams.takeCuts(watched, context.postedSources)
                input.post.perf?.add(PerfKeys.CUT_SOURCE_ROUNDS, cuts)
            } finally {
                locks.release(key, held)
            }
        }
    }

    private suspend fun runLocked(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
    ): TurnOutcome {
        notes.announce(context)
        val conflicts = branch.conflictingRecords(context)
        context.conflicts = conflicts
        context.completed += registry.completed(context.key).filterNot { it.id in conflicts }
        val extended = driver.sent.extend(context, registry.recordsFor(context.key))
        return extended?.let { extended(context, initialOuter, it) } ?: ordinary(context, initialOuter, body)
    }

    private suspend fun ordinary(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        body: CodeModeBody,
    ): TurnOutcome {
        val completedHistory = branch.canonicalize(context, body, wire.callbackIds(body))
        completedHistory.error?.let { return failure(it) }
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

    private fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(
            message,
            traits = FailureTraits(deterministic = true),
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
            salvagedUsage = noRequestUsage,
        )
}

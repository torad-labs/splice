// NEW: drives bounded outer code-mode scripts and their hidden upstream continuation rounds.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeOutcomeAccumulator
import splice.provider.codex.stream.CodeModeRecordFactory
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeRuntimeRun
import splice.provider.codex.stream.CodeModeRuntimeStarter
import splice.provider.codex.stream.CodeModeStreams
import splice.provider.codex.stream.batch.CodeModeAdmissionBodies
import splice.provider.codex.stream.batch.CodeModeBatchDispatch
import splice.provider.codex.stream.batch.CodeModeBatchLinks
import splice.provider.codex.stream.batch.CodeModeBatchPost
import splice.provider.codex.stream.batch.CodeModeBatchPreparation
import splice.provider.codex.stream.batch.CodeModeBatchRefusal
import splice.provider.codex.stream.batch.CodeModeBatchRewrite
import splice.provider.codex.stream.batch.CodeModeBatchStart
import splice.provider.codex.stream.batch.CodeModeGeneratedActions
import splice.provider.codex.stream.batch.CodeModeGeneratedContinuation
import splice.provider.codex.stream.batch.CodeModeRuntimeDispatch
import splice.provider.codex.stream.drive.CodeModeDriveProtocol
import splice.provider.codex.stream.drive.CodeModeDriveState
import splice.upstream.failure.CodeModeCapacityException
import java.io.IOException

internal class CodexCodeModeDriver(
    private val config: CodeModeBridgeConfig,
    private val run: CodeModeRuntimeRun,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val validation: CodexCodeModeValidation,
    private val machine: CodexCodeModeMachine,
    /** Each conversation's last post, which the next request extends while the client's history only grows. */
    val sent: CodeModeUpstreamHistory,
) {
    val streams = CodeModeStreams(config, registry, wire)
    private val protocol = CodeModeDriveProtocol(config)
    private val factory = CodeModeRecordFactory(config, wire)
    private val starter = CodeModeRuntimeStarter(run, registry, config)
    private val batches = CodeModeBatchDispatch(registry)
    private val preparation = CodeModeBatchPreparation(config, registry, wire, sent)

    private val dispatch = CodeModeRuntimeDispatch(
        starter,
        registry,
        machine,
        streams,
        sent,
        batches,
    )

    private val starting = CodeModeBatchStart(::startRuntime)
    private val generated = CodeModeGeneratedContinuation(
        streams,
        batches,
        CodeModeGeneratedActions(
            starting,
            CodeModeBatchPost(::post),
            CodeModeBatchRewrite(::continuation),
            CodeModeBatchRefusal(protocol::failure),
        ),
    )

    /** A redirectable post can yield a durable script while its upstream response remains live. */
    suspend fun post(context: CodeModeRunContext, initialOuter: GatewayCustomCall?, body: CodeModeBody): TurnOutcome {
        sent.sent(context, body)
        val post = context.link.post as? CodeModeRedirectablePost
            ?: return drive(context, initialOuter, body, sent.settled(context, context.link.post(body)))
        val admissionBody = CodeModeAdmissionBodies(wire).compact(context, body)
        val clientStart = sent.clientStart(context)
        val round = streams.begin(context, admissionBody, post) { call ->
            protocol.problem(context, listOf(call), call)?.let { error(it) }
            validation.outer(
                call.copy(
                    input = call.input.ifBlank {
                        "source pending"
                    },
                ),
                CodeModeSourceAdmission.PENDING,
            )?.let { error(it) }
            context.scripts++
            val record = factory.pending(context, call, admissionBody, clientStart)
            record.postedOn = sent.newest(context)
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
                val parked = (round.activeRecord ?: admitted)?.let(CodeModeBatchLinks::retained) == true
                // A cancelled first client step must release even a reader blocked writing to that client.
                if (clientCancelled) {
                    round.stopClientStep()
                } else if (!parked) {
                    round.cancel()
                }
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
        if (record == null) return drive(context, initialOuter, body, sent.settled(context, round.outcome()))
        streams.keep(record, round)
        val (running, advanced) = startRuntime(record, context, round)
        return round.localFailure ?: if (running.phase == CodeModePhase.COMPLETED) {
            finishGenerated(running, context, body)
        } else {
            streams.billFinished(running, advanced)
        }
    }

    suspend fun finishGenerated(record: CodeModeRecord, context: CodeModeRunContext, body: CodeModeBody): TurnOutcome =
        generated.finish(record, context, body)

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
        val calls = success.handoffs.customCalls
        if (calls.size > 1) {
            accumulated.absorb(success)
            val record = preparation.prepare(context, state.body, success)
            val (_, advanced) = startRuntime(record, context)
            val result = if (record.phase == CodeModePhase.COMPLETED) {
                finishGenerated(record, context, state.body)
            } else {
                advanced
            }
            return accumulated.finish(result)
        }
        val outer = state.suppliedOuter ?: calls.singleOrNull()
        val problem = protocol.problem(context, calls, outer)
        return when {
            problem != null -> {
                accumulated.absorb(success)
                accumulated.finishLocal(protocol.failure(problem))
            }
            outer == null -> accumulated.finish(success)
            else -> advanceScript(context, state, accumulated, success, outer)
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
            val rewritten = continuation(context, record, state.body)
            rewritten.error?.let { return accumulated.finishLocal(protocol.failure(it)) }
            state.body = checkNotNull(rewritten.body)
            accumulated.finish(post(context, null, state.body))
        }
    }

    /** The request that carries [record]'s output upstream: the last post extended, as Codex sends it, or else the
     *  client's history with every finished script placed, once. */
    private suspend fun continuation(
        context: CodeModeRunContext,
        record: CodeModeRecord,
        body: CodeModeBody,
    ): CodeModeRewrite {
        val records = (registry.recordsFor(context.key) + context.completed).distinctBy(CodeModeRecord::id)
        // In a recovery the round's partial already went up before the marker, so a script it finished goes on from
        // what followed the marker, never with the partial sent again.
        sent.extend(context, context.recovery?.upstream(records) ?: records)?.let { return CodeModeRewrite(it) }
        val posted = context.recovery?.upstream(context.completed) ?: context.completed
        val capture = posted.lastOrNull { it.id == record.id }
        return context.link.post.canonicalize(body, posted, context.turn.rendering.media, capture)
    }

    private suspend fun begin(
        context: CodeModeRunContext,
        outer: GatewayCustomCall,
        body: CodeModeBody,
        outcome: TurnOutcome.Success,
    ): Pair<CodeModeRecord?, TurnOutcome> {
        validation.outer(outer)?.let { return null to protocol.failure(it) }
        val record = factory.create(context, outer, body, outcome, sent.clientStart(context))
            ?: return null to protocol.failure("code mode requires a Responses input array")
        record.postedOn = sent.newest(context)
        return if (!registry.add(record)) {
            null to protocol.failure("code-mode registry capacity reached")
        } else {
            startRuntime(record, context)
        }
    }

    /** A proven failed boot retained unexecuted source, so an exact retry may start it. */
    suspend fun retryStart(record: CodeModeRecord, context: CodeModeRunContext, body: CodeModeBody): TurnOutcome {
        check(registry.add(record)) { "Code-mode record cannot restart" }
        val stream = streams.find(record)
        if (stream == null && record.sourceState?.itemComplete == false) {
            registry.lose(record, "upstream source state was lost; source was not rerun")
            return protocol.failure(record.error.orEmpty())
        }
        val (_, advanced) = startRuntime(record, context, stream)
        stream?.switching?.detach()
        return if (record.phase == CodeModePhase.COMPLETED) finishGenerated(record, context, body) else advanced
    }

    private suspend fun startRuntime(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound? = null,
    ): Pair<CodeModeRecord, TurnOutcome> = try {
        dispatch.selected(record, starter.select(record, stream, context.turn.toolPolicy), context, stream, starting)
    } catch (error: CancellationException) {
        starter.cancelled(record, error)
        throw error
    } catch (error: CodeModePersistenceException) {
        throw error
    } catch (error: CodeModeCapacityException) {
        record to starter.capacity(record, error, machine)
    } catch (error: IOException) {
        record to starter.failed(record, stream, error)
    } catch (error: splice.provider.codex.stream.CodeModeArgumentException) {
        registry.complete(record, error.message.orEmpty())
        record to splice.provider.codex.stream.CodeModeLocalOutcome.success()
    } catch (_: IllegalArgumentException) {
        registry.lose(record, "code-mode runtime rejected its input; source was not rerun")
        record to protocol.failure(record.error.orEmpty())
    } catch (_: RuntimeException) {
        record to starter.failed(record, null)
    }
}

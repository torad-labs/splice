// NEW: validates and resumes active or lost code-mode records from client tool results, and (V4-336)
// reads what else the client sent while a script was parked.
package splice.provider.codex

import kotlinx.serialization.json.JsonElement
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.upstream.codemode.CodeModeResult
import splice.upstream.sse.WireSink

private data class CodeModeResultBatch(
    val results: Map<String, CodeModeResult>,
    val error: String?,
)

internal class CodexCodeModeResume(
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val validation: CodexCodeModeValidation,
    private val machine: CodexCodeModeMachine,
    private val driver: CodexCodeModeDriver,
) {
    suspend fun active(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
    ): TurnOutcome {
        val source = driver.streams.find(record)
        record.error?.let { return if (source?.sourceInterrupted == true) source.outcome() else failure(it) }
        val attached = context.copy(sink = driver.streams.attach(record, context.sink))
        attached.completed += context.completed
        return try {
            if (record.lastDigest == context.digest && record.pending.isNotEmpty()) {
                val pending = record.visiblePending().filter { it.clientId !in record.results }
                if (pending.isNotEmpty()) return machine.emit(record, pending, attached.sink)
            }
            fresh(record, attached, bodyJson)
        } finally {
            driver.streams.endStep(record)
        }
    }

    /** A lost cell can never resume, so the same request retried can never succeed: rather than
     *  failing until new user content arrives, the record completes with its interruption evidence
     *  (results so far, unresolved calls, the reason) and the model continues from there. */
    suspend fun lost(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
    ): TurnOutcome {
        val detail = record.error ?: "code-mode process state was lost; source was not rerun"
        val supplied = suppliedResults(record, context.turn, mode = CodeModeResultMode.INTERRUPT)
        supplied.error?.let { return failure(it) }
        registry.acceptResults(record, context.digest, supplied.results, context.turn.toolMedia)
        val interrupted = machine.interrupt(record, detail)
        return if (interrupted is TurnOutcome.Failure) interrupted else continueUpstream(record, context, bodyJson)
    }

    private suspend fun fresh(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
    ): TurnOutcome {
        val extra = wire.extraContent(bodyJson, record, candidateMedia(record, context.turn))
        val hasExtraContent = extra != CodeModeExtra.NONE
        val mode = if (hasExtraContent) CodeModeResultMode.INTERRUPT else CodeModeResultMode.RESUME
        val supplied = suppliedResults(record, context.turn, mode)
        supplied.error?.let { return failure(it) }
        registry.acceptResults(record, context.digest, supplied.results, context.turn.toolMedia)
        record.pending.firstOrNull { it.name !in context.turn.tools }?.let { pending ->
            val message = "code-mode tool '${pending.name}' is no longer in the current tool catalog"
            return reject(record, context, bodyJson, hasExtraContent, message)
        }
        return accept(record, context, bodyJson, extra, supplied.results)
    }

    private suspend fun accept(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
        extra: CodeModeExtra,
        supplied: Map<String, CodeModeResult>,
    ): TurnOutcome {
        val advanced = advance(record, context, extra, supplied)
        return if (record.phase != CodeModePhase.COMPLETED) {
            advanced
        } else {
            continueUpstream(record, context, bodyJson)
        }
    }

    private suspend fun advance(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        extra: CodeModeExtra,
        supplied: Map<String, CodeModeResult>,
    ): TurnOutcome = when {
        interrupts(extra, record) -> {
            machine.interrupt(record)
        }
        context.disableParallel && record.pending.any { !it.exposed } -> {
            exposeNext(record, supplied, context.sink)
        }
        else -> {
            val batch = runtimeResults(record)
            registry.changes.edit(record) { it.pending.clear() }
            machine.advance(
                record,
                context.turn,
                context.disableParallel,
                batch,
                context.sink,
                driver.streams.find(record),
            )
        }
    }

    private suspend fun reject(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
        hasExtraContent: Boolean,
        message: String,
    ): TurnOutcome {
        val rejected = machine.poison(record, message)
        if (!hasExtraContent) return rejected
        val interrupted = machine.interrupt(record, "additional client content arrived; $message")
        return if (interrupted is TurnOutcome.Failure) interrupted else continueUpstream(record, context, bodyJson)
    }

    private suspend fun continueUpstream(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
    ): TurnOutcome {
        return driver.finishGenerated(record, context, bodyJson)
    }

    private suspend fun exposeNext(
        record: CodeModeRecord,
        supplied: Map<String, CodeModeResult>,
        sink: WireSink,
    ): TurnOutcome {
        registry.changes.save(record) { live -> live.pending.first { !it.exposed }.exposed = true }
        val pending = record.visiblePending().filter {
            it.clientId !in record.results && it.clientId !in supplied
        }
        return machine.emit(record, pending, sink)
    }

    private fun suppliedResults(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
        mode: CodeModeResultMode = CodeModeResultMode.RESUME,
    ): CodeModeResultBatch {
        val exposed = record.visiblePending().map(CodeModePending::clientId).toSet()
        val current = turn.toolResults.filter { it.id in exposed && it.id !in record.results }
            .associateBy(CodeModeResult::id)
        return CodeModeResultBatch(
            results = current,
            error = validation.results(record, turn, exposed, current, mode),
        )
    }

    /** V4-179: the follow-ups of results this record has NOT accepted yet. A result already in the
     *  record without a media entry is legacy and stays that way — it never gains media here. */
    private fun candidateMedia(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
    ): Map<String, List<JsonElement>> = turn.toolMedia.filterKeys { it !in record.results }

    /**
     * V4-336: whether what the client added stops the script. Role=system content (a peer's message,
     * a task notification, a hook's output) waits for the script's output once every call the script
     * issued has its result, and the rewrite keeps it after the record's canonical output: live, 85 of
     * the 86 records cut by "additional client content arrived" had every result back, and the model
     * re-issued the batch. It still stops a script with a call unanswered, or one a sequential batch has
     * not exposed yet. Steering (anything the operator's side gave) stops the script as it always did.
     */
    private fun interrupts(extra: CodeModeExtra, record: CodeModeRecord): Boolean = when (extra) {
        CodeModeExtra.NONE -> false
        CodeModeExtra.SYSTEM -> !answered(record)
        CodeModeExtra.STEERING -> true
    }

    private fun answered(record: CodeModeRecord): Boolean =
        record.pending.all { it.clientId in record.results }

    private fun runtimeResults(record: CodeModeRecord): List<CodeModeResult> = record.pending.map { pending ->
        val result = record.results.getValue(pending.clientId)
        CodeModeResult(pending.runtimeId, result.output, result.isError)
    }

    private fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(
            message,
            deterministic = true,
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
        )
}

/**
 * What the client added after a parked script's baseline that the script does not own (V4-336):
 * nothing; only role=system messages, which Claude Code sends for a peer's message, a task
 * notification or a hook's output, and which wait for the script's output when its every call is
 * answered; or STEERING, anything else the operator's side gave (typed text, an image, a replay item
 * nobody expected, a baseline that no longer matches), which stops the script as it always did.
 */
internal enum class CodeModeExtra { NONE, SYSTEM, STEERING }

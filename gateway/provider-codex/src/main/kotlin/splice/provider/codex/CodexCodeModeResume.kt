// NEW: validates and resumes active or lost code-mode records from client tool results.
package splice.provider.codex

import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeResult
import splice.spi.WireSink

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
        record.error?.let { return failure(it, ErrorType.API_ERROR) }
        if (record.lastDigest == context.digest && record.pending.isNotEmpty()) {
            val pending = record.visiblePending().filter { it.clientId !in record.results }
            if (pending.isNotEmpty()) return machine.emit(pending, context.sink)
        }
        return fresh(record, context, bodyJson)
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
        registry.acceptResults(record, context.digest, supplied.results)
        val interrupted = machine.interrupt(record, detail)
        return if (interrupted is TurnOutcome.Failure) interrupted else continueUpstream(record, context, bodyJson)
    }

    private suspend fun fresh(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
    ): TurnOutcome {
        val hasExtraContent = wire.hasExtraContent(bodyJson, record)
        val mode = if (hasExtraContent) CodeModeResultMode.INTERRUPT else CodeModeResultMode.RESUME
        val supplied = suppliedResults(record, context.turn, mode)
        supplied.error?.let { return failure(it) }
        registry.acceptResults(record, context.digest, supplied.results)
        record.pending.firstOrNull { it.name !in context.turn.tools }?.let { pending ->
            val message = "code-mode tool '${pending.name}' is no longer in the current tool catalog"
            return reject(record, context, bodyJson, hasExtraContent, message)
        }
        return accept(record, context, bodyJson, hasExtraContent, supplied.results)
    }

    private suspend fun accept(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        bodyJson: String,
        hasExtraContent: Boolean,
        supplied: Map<String, CodeModeResult>,
    ): TurnOutcome {
        val advanced = advance(record, context, hasExtraContent, supplied)
        return if (record.phase != CodeModePhase.COMPLETED) {
            advanced
        } else {
            continueUpstream(record, context, bodyJson)
        }
    }

    private suspend fun advance(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        hasExtraContent: Boolean,
        supplied: Map<String, CodeModeResult>,
    ): TurnOutcome = when {
        hasExtraContent -> {
            machine.interrupt(record)
        }
        context.disableParallel && record.pending.any { !it.exposed } -> {
            exposeNext(record, supplied, context.sink)
        }
        else -> {
            val batch = runtimeResults(record)
            record.pending.clear()
            machine.advance(
                record,
                context.turn,
                context.disableParallel,
                batch,
                context.sink,
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
        val rewritten = wire.canonicalize(bodyJson, registry.completed(record.key))
        rewritten.error?.let { return failure(it) }
        val canonicalBody = checkNotNull(rewritten.bodyJson)
        return driver.drive(context, null, canonicalBody, context.post(canonicalBody))
    }

    private suspend fun exposeNext(
        record: CodeModeRecord,
        supplied: Map<String, CodeModeResult>,
        sink: WireSink,
    ): TurnOutcome {
        record.pending.first { !it.exposed }.exposed = true
        registry.save()
        val pending = record.visiblePending().filter {
            it.clientId !in record.results && it.clientId !in supplied
        }
        return machine.emit(pending, sink)
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

    private fun runtimeResults(record: CodeModeRecord): List<CodeModeResult> = record.pending.map { pending ->
        val result = record.results.getValue(pending.clientId)
        CodeModeResult(pending.runtimeId, result.output, result.isError)
    }

    private fun failure(message: String, type: ErrorType = ErrorType.INVALID_REQUEST): TurnOutcome.Failure =
        TurnOutcome.Failure(type, message)
}

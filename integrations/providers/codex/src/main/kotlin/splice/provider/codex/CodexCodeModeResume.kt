// NEW: validates and resumes active or lost code-mode records from client tool results, and (V4-336)
// reads what else the client sent while a script was parked.
package splice.provider.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesContextMessage
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
        record.error?.let { return failure(it) }
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
        val rewritten = wire.canonicalize(bodyJson, registry.completed(record.key), context.turn.toolMedia)
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

/**
 * The logical items after the baseline (and after its continuity, while that is intact) that are
 * neither the script's callbacks nor their follow-ups, and any replay item in the tail nothing put there.
 */
internal class CodeModeExtraContent(
    private val codec: CodexCodeModeHistoryCodec,
    private val ownership: CodeModeOwnership,
) {
    fun of(bodyJson: String, record: CodeModeRecord, candidateMedia: Map<String, List<JsonElement>>): CodeModeExtra {
        val projected = onBaseline(bodyJson, record) ?: return CodeModeExtra.STEERING
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val logicalExtra = unownedItems(projected.logicalItems, record, owned, candidateMedia)
        return when {
            unexpectedReplay(projected, record, owned) || logicalExtra.any { !isSystemMessage(it) } ->
                CodeModeExtra.STEERING
            logicalExtra.isNotEmpty() -> CodeModeExtra.SYSTEM
            else -> CodeModeExtra.NONE
        }
    }

    /** The request's conversation, or null when it no longer starts with the record's baseline. */
    private fun onBaseline(bodyJson: String, record: CodeModeRecord): ResponsesCodeModeInput? {
        val input = codec.root(bodyJson)?.second ?: return null
        val projected = codec.conversation(codec.projection.project(input)).body
        val validBaseline = codec.validFullPrefix(input, record) ||
            codec.validPrefix(projected.logicalItems, record)
        return projected.takeIf { validBaseline }
    }

    private fun unownedItems(
        items: List<JsonElement>,
        record: CodeModeRecord,
        owned: Set<String>,
        candidateMedia: Map<String, List<JsonElement>>,
    ): List<JsonElement> {
        val ownedFollowUps = ownership.followUps(items, record, candidateMedia)
        val tailStart = record.baselineLogicalCount
        val afterContinuity = if (codec.continuityAt(items, tailStart, record.continuity)) {
            tailStart + record.continuity.size
        } else {
            tailStart
        }
        return (afterContinuity until items.size).filter { index ->
            !ownership.isCallback(items[index], owned) && index !in ownedFollowUps
        }.map(items::get)
    }

    /** A replay item in the tail (from the baseline's end on) that neither the record put there nor an
     *  owned callback carries. Replay inside the baseline is history, held by the baseline's digest and
     *  native segments before this runs; reasoning before an earlier ordinary call is never recorded
     *  (native segments keep only replay no function_call follows), and read as new it stopped every
     *  resume in a conversation with reasoning (live after the V4-336 install: 35 of 49 records). */
    private fun unexpectedReplay(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        owned: Set<String>,
    ): Boolean {
        val baselineReplay = record.nativeSegments.map { it.logicalOffset to it.items }.toSet()
        val continuityReplay = record.continuityReplay.map {
            record.baselineLogicalCount + it.logicalOffset to it.items
        }.toSet()
        return projected.replayItems.any { replay ->
            val slot = replay.logicalOffset to replay.items
            val expected = slot in baselineReplay || slot in continuityReplay
            replay.logicalOffset >= record.baselineLogicalCount && !expected && replay.callbackId !in owned
        }
    }

    /** A role=system message: how Claude Code sends a peer's message, a task notification or a hook's
     *  output (the live claudex wire, 2026-09-26). V4-390: a lite turn sends it as the dialect's
     *  context message; the role=system form stays recognized for requests built before that. */
    private fun isSystemMessage(element: JsonElement): Boolean {
        val item = element as? JsonObject
        val message = codec.string(item, CODE_MODE_FIELD_TYPE) in MESSAGE_TYPES
        return (message && codec.string(item, CODE_MODE_FIELD_ROLE) == ResponsesContextMessage.CLIENT_ROLE) ||
            ResponsesContextMessage.isContext(item)
    }
}

/** The builder writes a plain {role, content} message with no `type`; an explicit one says message. */
private val MESSAGE_TYPES = setOf("", "message")

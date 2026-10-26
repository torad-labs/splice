// NEW: validates outer scripts, runtime calls, and retry-safe client result batches for code mode.
package splice.provider.codex

import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeProtocol
import splice.upstream.codemode.CodeModeResult

internal enum class CodeModeSourceAdmission { PENDING, EXECUTABLE }

internal enum class CodeModeResultMode { RESUME, INTERRUPT }

internal class CodexCodeModeValidation(private val config: CodeModeBridgeConfig) {
    fun fitsOutput(value: String): Boolean =
        value.length <= config.bounds.maxOutputChars && CodeModeLimits.fitsText(value)

    /** A client result enters bounded: an oversized output is truncated with the marker, never rejected. */
    fun admit(result: CodeModeResult): CodeModeResult {
        val payload = result.payload as? JsonPrimitive
        return result.copy(
            output = CodeModeLimits.boundedText(result.output, config.bounds.maxOutputChars),
            payload = if (payload?.isString == true) {
                JsonPrimitive(CodeModeLimits.boundedText(payload.content, config.bounds.maxOutputChars))
            } else {
                result.payload
            },
        )
    }

    fun outer(
        call: GatewayCustomCall,
        admission: CodeModeSourceAdmission = CodeModeSourceAdmission.EXECUTABLE,
    ): String? = when {
        incomplete(call, admission) -> "exec item did not complete"
        call.name !in setOf(CODE_MODE_TOOL_NAME, LEGACY_CODE_MODE_TOOL_NAME, CODE_MODE_WAIT_TOOL_NAME) ->
            "unsupported custom tool call '${call.name.ifEmpty { "<unnamed>" }}'"
        call.callId.isBlank() -> "exec call is missing call_id"
        call.input.isBlank() && call.name == LEGACY_CODE_MODE_TOOL_NAME -> "exec call is missing JavaScript input"
        call.input.length > config.bounds.maxSourceChars || !CodeModeLimits.fitsText(call.input) ->
            "exec source exceeds the size limit"
        else -> null
    }

    private fun incomplete(call: GatewayCustomCall, admission: CodeModeSourceAdmission): Boolean {
        if (admission == CodeModeSourceAdmission.PENDING) return false
        return "status" in call.raw && JsonScalars.str(call.raw, "status") != "completed"
    }

    fun calls(record: CodeModeRecord, tools: Set<String>, calls: List<CodeModeCall>): String? {
        val duplicateIds = calls.groupingBy(CodeModeCall::id).eachCount().filterValues { it > 1 }.keys
        val unknown = calls.firstOrNull { it.name !in tools }
        return when {
            calls.isEmpty() -> "code-mode runtime yielded an empty call batch"
            config.bounds.maxCalls?.let { record.progress.totalCalls.toLong() + calls.size > it } == true ->
                "code-mode call limit exceeded"
            duplicateIds.isNotEmpty() -> "code-mode runtime repeated call ids: $duplicateIds"
            unknown != null -> "code-mode tool '${unknown.name}' is not in the current tool catalog"
            else -> null
        }
    }

    fun results(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
        exposed: Set<String>,
        current: Map<String, CodeModeResult>,
        mode: CodeModeResultMode = CodeModeResultMode.RESUME,
    ): String? {
        val relevant = turn.toolResults.filter { it.id in record.clientIds() }
        val duplicateIds = relevant.groupingBy(CodeModeResult::id).eachCount().filterValues { it > 1 }.keys
        val conflict = relevant.firstOrNull { prior -> conflicts(record, turn, prior) }
        val oversized = current.values.firstOrNull { !fitsOutput(it.output) }
        val unexposedIds = record.progress.pending.filterNot(CodeModePending::exposed)
            .map(CodeModePending::clientId).toSet()
        val unexposed = relevant.firstOrNull { it.id in unexposedIds }
        return when {
            duplicateIds.isNotEmpty() -> "duplicate code-mode tool results: $duplicateIds"
            conflict != null -> "conflicting replay for code-mode tool result '${conflict.id}'"
            unexposed != null -> "code-mode tool result '${unexposed.id}' was not exposed to the client"
            oversized != null -> "code-mode tool result '${oversized.id}' exceeds the size limit"
            mode == CodeModeResultMode.INTERRUPT -> null
            else -> pendingFrameProblem(record, current)
        }
    }

    /** An accepted result replayed as something else. V4-179: identity includes the media — a replay
     *  carrying different pixels under an accepted id with the same text is a different result. A
     *  LEGACY id (accepted before media was captured, no entry) is compared on text alone, as it
     *  always was, and its text was rendered by the previous daemon with the previous markers: the
     *  same client blocks rendered that way ([CodeModeResultRendering.legacy]) are the same
     *  result, so an upgrade under a parked script does not turn every replay into a conflict. */

    /** A fork altered an already accepted callback; it is not the running cell's next result. */
    fun conflicts(record: CodeModeRecord, turn: CodexCodeModeBridge.Turn): Boolean =
        turn.toolResults.any { conflicts(record, turn, it) }

    private fun conflicts(record: CodeModeRecord, turn: CodexCodeModeBridge.Turn, prior: CodeModeResult): Boolean {
        val accepted = record.results[prior.id] ?: return false
        val cleared = turn.rendering.verifiedCleared[record.id]?.contains(prior.id) == true
        val media = record.accepted.media(prior.id)
        // Transport disposition is server-owned; an old receipt keeps its original contract on replay.
        val payload = if (prior.disposition == accepted.disposition) prior.payload else accepted.payload
        val receipt = prior.copy(payload = payload, disposition = accepted.disposition, origin = accepted.origin)
        return when {
            cleared -> accepted.isError != prior.isError
            media == null -> accepted != receipt && accepted != turn.rendering.legacy.firstOrNull { it.id == prior.id }
            else -> accepted != receipt || media != turn.rendering.media[prior.id].orEmpty()
        }
    }

    private fun pendingFrameProblem(record: CodeModeRecord, current: Map<String, CodeModeResult>): String? {
        // Lost continuations preserve partial evidence; they never send a frame to a worker.
        if (record.phase != CodeModePhase.ACTIVE) return null
        val results = record.progress.pending.map { pending ->
            val result = current[pending.clientId] ?: record.results[pending.clientId]
            // Reserve empty future-result fields while accumulating a sequential batch. No placeholders are sent.
            CodeModeResult(pending.runtimeId, result?.output.orEmpty(), result?.isError ?: false)
        }
        return try {
            CodeModeProtocol.resultChunks(results, CodeModeLimits.MAX_RESULTS_PER_FRAME)
            null
        } catch (_: IllegalArgumentException) {
            "code-mode result frame exceeds the size limit"
        }
    }
}

// NEW: validates outer scripts, runtime calls, and retry-safe client result batches for code mode.
package splice.provider.codex

import splice.core.turn.GatewayCustomCall
import splice.spi.CodeModeCall
import splice.spi.CodeModeLimits
import splice.spi.CodeModeProtocol
import splice.spi.CodeModeResult

internal enum class CodeModeResultMode { RESUME, INTERRUPT }

internal class CodexCodeModeValidation(private val config: CodeModeBridgeConfig) {
    fun fitsOutput(value: String): Boolean = value.length <= config.maxOutputChars && CodeModeLimits.fitsText(value)

    fun outer(call: GatewayCustomCall): String? = when {
        call.name != CODE_MODE_TOOL_NAME -> "unsupported custom tool call '${call.name.ifEmpty { "<unnamed>" }}'"
        call.callId.isBlank() -> "splice_exec call is missing call_id"
        call.input.isBlank() -> "splice_exec call is missing JavaScript input"
        call.input.length > config.maxSourceChars || !CodeModeLimits.fitsText(call.input) ->
            "splice_exec source exceeds the size limit"
        else -> null
    }

    fun calls(record: CodeModeRecord, tools: Set<String>, calls: List<CodeModeCall>): String? {
        val duplicateIds = calls.groupingBy(CodeModeCall::id).eachCount().filterValues { it > 1 }.keys
        val unknown = calls.firstOrNull { it.name !in tools }
        val oversized = calls.firstOrNull { !fitsOutput(it.arguments.toString()) }
        return when {
            calls.isEmpty() -> "code-mode runtime yielded an empty call batch"
            record.totalCalls + calls.size > config.maxCalls -> "code-mode call limit exceeded"
            duplicateIds.isNotEmpty() -> "code-mode runtime repeated call ids: $duplicateIds"
            unknown != null -> "code-mode tool '${unknown.name}' is not in the current tool catalog"
            oversized != null -> "code-mode tool arguments exceed the size limit"
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
        val conflict = relevant.firstOrNull { prior ->
            record.results[prior.id]?.let { it != prior } == true
        }
        val missing = (exposed - record.results.keys - current.keys)
            .takeIf { mode == CodeModeResultMode.RESUME }.orEmpty()
        val oversized = current.values.firstOrNull { !fitsOutput(it.output) }
        val unexposedIds = record.pending.filterNot(CodeModePending::exposed).map(CodeModePending::clientId).toSet()
        val unexposed = relevant.firstOrNull { it.id in unexposedIds }
        return when {
            duplicateIds.isNotEmpty() -> "duplicate code-mode tool results: $duplicateIds"
            conflict != null -> "conflicting replay for code-mode tool result '${conflict.id}'"
            unexposed != null -> "code-mode tool result '${unexposed.id}' was not exposed to the client"
            missing.isNotEmpty() -> "missing code-mode tool results: $missing"
            oversized != null -> "code-mode tool result '${oversized.id}' exceeds the size limit"
            mode == CodeModeResultMode.INTERRUPT -> null
            else -> pendingFrameProblem(record, current)
        }
    }

    private fun pendingFrameProblem(record: CodeModeRecord, current: Map<String, CodeModeResult>): String? {
        // Lost continuations preserve partial evidence; they never send a frame to a worker.
        if (record.phase != CodeModePhase.ACTIVE) return null
        val results = record.pending.map { pending ->
            val result = current[pending.clientId] ?: record.results[pending.clientId]
            // Reserve empty future-result fields while accumulating a sequential batch. No placeholders are sent.
            CodeModeResult(pending.runtimeId, result?.output.orEmpty(), result?.isError ?: false)
        }
        return if (CodeModeProtocol.fitsResultFrame(results)) null else "code-mode result frame exceeds the size limit"
    }
}

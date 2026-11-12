package splice.provider.codex.callbacks

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeExtra
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeResultMode
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeValidation
import splice.upstream.codemode.CodeModeResult
internal data class CodeModeResultBatch(
    val results: Map<String, CodeModeResult>,
    val error: String?,
)

/** Validates client callback receipts and maps accepted results back to runtime identities. */
internal class CodeModeResumeResults(private val validation: CodexCodeModeValidation) {
    fun supplied(
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
    fun media(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
    ): Map<String, List<JsonElement>> = turn.rendering.media.filterKeys { it !in record.results }

    /**
     * V4-336: whether what the client added stops the script. Role=system content (a peer's message,
     * a task notification, a hook's output) waits for the script's output once every call the script
     * issued has its result, and the rewrite keeps it after the record's canonical output: live, 85 of
     * the 86 records cut by "additional client content arrived" had every result back, and the model
     * re-issued the batch. It still stops a script with a call unanswered, or one a sequential batch has
     * not exposed yet. Steering (anything the operator's side gave) stops the script as it always did.
     */
    fun interrupts(extra: CodeModeExtra, record: CodeModeRecord): Boolean = when (extra) {
        CodeModeExtra.NONE -> false
        CodeModeExtra.SYSTEM -> !answered(record)
        CodeModeExtra.STEERING -> true
    }

    private fun answered(record: CodeModeRecord): Boolean =
        record.progress.pending.all { it.clientId in record.results }

    fun runtime(record: CodeModeRecord): List<CodeModeResult> = record.progress.pending
        .filter { it.clientId in record.results }
        .map { pending ->
            val result = record.results.getValue(pending.clientId)
            result.copy(id = pending.runtimeId)
        }
}

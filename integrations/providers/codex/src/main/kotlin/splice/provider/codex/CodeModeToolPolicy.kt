package splice.provider.codex

import splice.provider.codex.catalog.CodeModePresentation

/** Current step policy travels with dispatch, including model-owned image capability. */
public data class CodeModeToolPolicy(
    val deferredNames: Set<String> = emptySet(),
    val toolDescriptionFirst: Boolean = false,
    val supportsOriginal: Boolean = false,
    val showCellOverhead: Boolean = false,
    val inputSchemaMaxBytes: Int? = null,
    val presentation: CodeModePresentation = CodeModePresentation(),
    /** Client tools a cell's search loaded mid-conversation (a `tool_reference` in a result the cell read). exec's
     *  manual leaves them out as the turns before the load did, so the request's first item keeps its bytes and the
     *  prompt cache keeps the conversation (2026-10-25); the search's own result describes them instead. */
    val loadedNames: Set<String> = emptySet(),
) {
    val defaultExecYieldTimeMs: Long get() = presentation.defaultExecYieldTimeMs
    val modelMessages: kotlinx.serialization.json.JsonObject? get() = presentation.modelMessages
    val imageDetailVisible: Boolean get() = presentation.imageDetailVisible
}

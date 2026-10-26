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
) {
    val defaultExecYieldTimeMs: Long get() = presentation.defaultExecYieldTimeMs
    val modelMessages: kotlinx.serialization.json.JsonObject? get() = presentation.modelMessages
    val imageDetailVisible: Boolean get() = presentation.imageDetailVisible
}

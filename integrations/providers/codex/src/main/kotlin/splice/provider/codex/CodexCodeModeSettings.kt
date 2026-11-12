package splice.provider.codex

import splice.provider.codex.catalog.CodeModeModelCatalog
import splice.provider.codex.catalog.NoCodeModeModelCatalog

/** Session settings corresponding to Codex's CodeModeConfig and description-order feature. */
public data class CodexCodeModeSettings(
    val defaultExecYieldTimeMs: Long = splice.upstream.codemode.CodeModeManual.DEFAULT_EXEC_YIELD_TIME_MS,
    val toolDescriptionFirst: Boolean = false,
    val showCellOverhead: Boolean = false,
    val toolInputSchemaMaxBytes: Int? = null,
    val modelCatalog: CodeModeModelCatalog = NoCodeModeModelCatalog,
    val unifiedImageBudget: Boolean = false,
)

/** Reads the head's Code Mode controls through the same normalized knob view as the rest of the provider. */
public object CodexCodeModeConfiguration {
    public fun read(config: splice.core.config.SpliceConfig): CodexCodeModeSettings = CodexCodeModeSettings(
        defaultExecYieldTimeMs = config.codeModeDefaultExecYieldTimeMs,
        toolDescriptionFirst = config.codeModeToolDescriptionFirst,
        showCellOverhead = config.codeModeShowCellOverhead,
        // Schema-bearing protocol messages are capped at 64 MiB, so larger declaration budgets are equivalent.
        toolInputSchemaMaxBytes = config.codeModeToolInputSchemaMaxBytes?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        unifiedImageBudget = config.codeModeUnifiedImageBudget,
    )
}

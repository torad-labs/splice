package splice.provider.codex.catalog

import kotlinx.serialization.json.JsonObject
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.availability.CodeModeToolMode

/** Model-owned wording and the source feature predicate captured for one actual execution. */
public data class CodeModePresentation(
    val defaultExecYieldTimeMs: Long = CodeModeManual.DEFAULT_EXEC_YIELD_TIME_MS,
    val modelMessages: JsonObject? = null,
    val imageDetailVisible: Boolean = true,
    val toolMode: CodeModeToolMode = CodeModeToolMode.CODE_MODE_ONLY,
)

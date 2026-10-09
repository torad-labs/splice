// NEW: how one turn's code-mode results render to the wire, beside the results themselves.
package splice.provider.codex

import kotlinx.serialization.json.JsonElement
import splice.upstream.codemode.CodeModeResult

/** V4-179: the two renderings of a turn's code-mode results that the script itself never sees. */
public data class CodeModeResultRendering(
    /** Per code-mode result id, the follow-up wire items its images render to (the ordinary dialect policy,
     *  rendered once by CodexCodeModeTurnBuilder). The record persists them and the history replays them. */
    val media: Map<String, List<JsonElement>> = emptyMap(),
    /** The same results rendered with the V4-178 markers, for replay identity against a record the previous
     *  daemon wrote (see CodexCodeModeValidation.conflicts). */
    val legacy: List<CodeModeResult> = emptyList(),
)

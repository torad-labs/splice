// NEW: the native model history a record carries forward.
package splice.provider.codex

import kotlinx.serialization.json.JsonElement

/** The model's own items a record carries: the native [segments] placed in the history, and the [continuity]
 *  items with their native [replay] that the next turn repeats. */
internal data class CodeModeNativeContinuity(
    var segments: List<CodeModeNativeSegment>,
    var continuity: List<JsonElement>,
    var replay: List<CodeModeNativeSegment>,
)

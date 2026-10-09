// NEW: the exec call a record was made from, with the history it was built on.
package splice.provider.codex

import kotlinx.serialization.json.JsonObject

/** The model's exec call that started the script: its raw item, call id and [source], and the [baseline] it sat on. */
internal data class CodeModeOrigin(
    var outer: JsonObject,
    val outerCallId: String,
    var source: String,
    val baseline: CodeModeBaseline,
)

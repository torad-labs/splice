// NEW: committed journal envelope contracts, independent of file transport and generated cell encoding.
package splice.provider.codex.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeRecordSnapshot

/** Each committed entry replaces only changed records and the conversation's expiry markers. */
@Serializable
internal data class CodeModeStateDelta(
    val key: String,
    val records: List<CodeModeRecordSnapshot>,
    val removed: Set<String>,
    val expired: List<CodeModeExpiredSnapshot>,
)

@Serializable
internal data class CodeModeCellPatch(val id: String, val fields: JsonObject)

/** An older full-cell decoder must reject this entry's absent mandatory records field, never lose heavy fields. */
@Serializable
internal data class CodeModeStatePatch(
    val key: String,
    val patches: List<CodeModeCellPatch>,
    val expired: List<CodeModeExpiredSnapshot>,
)

// NEW: LAYOUT-01 — the one error body the launch, wrap and resume surfaces answer with, held once so
// the three slices refuse byte-identically (it is ControlPayloads.errorJson's shape).
package splice.launch

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LaunchReplies {
    fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
}

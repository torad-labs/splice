// NEW: the body the control plane answers with on its routes when it refuses: the error every refusal carries.
// Stateless, so they live on an object and not as loose top-level functions (the walls forbid those).
package splice.app.control.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ControlBodies {
    /** The `{"error": message}` body a refused route answers with. */
    fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
}

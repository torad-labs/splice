// NEW: the two bodies the control plane answers with on its routes: the error every refusal carries, and the
// acknowledgement. Stateless, so they live on an object and not as loose top-level functions (the walls forbid those).
package splice.app.control.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ControlBodies {
    /** The `{"error": message}` body a refused route answers with. */
    fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

    /** The `{"ok":true}` acknowledgement, the /api/daemon/shutdown ack body. */
    fun okJson(): String = buildJsonObject { put("ok", true) }.toString()
}

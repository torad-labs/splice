package splice.app.peer.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Retains only lifecycle metadata from the desktop's versioned stream; never retains conversation content. */
internal class CodexDesktopState {
    private var revision: Long? = null
    private var status: String? = null

    /** A snapshot or contiguous patches may update threadRuntimeStatus. A gap requires a fresh connection. */
    fun accept(change: JsonObject): String? {
        val next = checkNotNull(JsonScalars.long(change, "revision")) { "desktop status has no revision" }
        when (JsonScalars.str(change, "type")) {
            "snapshot" -> {
                val state = checkNotNull(change["conversationState"] as? JsonObject)
                status = checkNotNull(JsonScalars.str(state["threadRuntimeStatus"] as? JsonObject, "type"))
            }
            "patches" -> {
                check(revision != null && revision == JsonScalars.long(change, "baseRevision")) {
                    "desktop thread stream lost a revision"
                }
                checkNotNull(change["patches"] as? JsonArray).forEach { patch -> update(patch as? JsonObject) }
            }
            else -> return null
        }
        revision = next
        return status
    }

    private fun update(patch: JsonObject?) {
        val path = patch?.get("path") as? JsonArray ?: return
        if (path.firstOrNull()?.let { JsonScalars.str(it) } != "threadRuntimeStatus") return
        check(JsonScalars.str(patch, "op") in setOf("add", "replace")) { "desktop runtime status was removed" }
        when {
            path.size == 1 -> status = checkNotNull(JsonScalars.str(patch["value"] as? JsonObject, "type"))
            path.size == 2 && JsonScalars.str(path[1]) == "type" -> {
                status = checkNotNull(JsonScalars.str(patch["value"]))
            }
        }
    }
}

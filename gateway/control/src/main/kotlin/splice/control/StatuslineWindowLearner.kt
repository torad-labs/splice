// NEW (2026-09-05): what a status-line post teaches the head about the window its session really
// runs with. Claude Code computes `context_window.context_window_size` for its current model in
// THIS process — for an env-governed id that is the launch env, the number the head must scale
// this session's counts against (ClientWindows). A "[1m]" row or a "claude-" id is sized from the
// id (cli 2.1.257 `PL()`), so those posts say nothing about the env and record nothing.
package splice.control

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog

internal class StatuslineWindowLearner(
    private val catalog: ModelCatalog?,
    private val store: ClientWindows?,
) {
    fun learn(root: JsonObject) {
        val id = text((root["model"] as? JsonObject)?.get("id")) ?: return
        if (catalog?.envGoverned(id) != true) return
        val size = long((root["context_window"] as? JsonObject)?.get("context_window_size"))
        store?.record(text(root["session_id"]), size)
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun long(element: JsonElement?): Long? = (element as? JsonPrimitive)?.longOrNull
}

// NEW: what a status-line post teaches the head about the window its session really
// runs with (2026-09-05). Claude Code computes `context_window.context_window_size` for its current model in
// THIS process — for an env-governed id that is the launch env; for a `claude-` id it is the
// actual divisor even when the client ignores that env. A "[1m]" id or presented row is sized
// from the selector and does not teach the running session's unknown window.
package splice.usage.statusline

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.util.JsonScalars

internal class StatuslineWindowLearner(
    private val catalog: ModelCatalog?,
    private val store: ClientWindows?,
) {
    fun learn(root: JsonObject) {
        val id = text((root["model"] as? JsonObject)?.get("id")) ?: return
        val current = catalog ?: return
        val learnedClaude = id.startsWith("claude-") && !current.presented.selectorSized(id)
        if (!current.envGoverned(id) && !learnedClaude) return
        val size = long((root["context_window"] as? JsonObject)?.get("context_window_size"))
        store?.record(text(root["session_id"]), size)
    }

    private fun text(element: JsonElement?): String? = JsonScalars.str(element)

    private fun long(element: JsonElement?): Long? = JsonScalars.str(element)?.toLongOrNull()
}

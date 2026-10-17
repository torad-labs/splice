// NEW: Oct 15, 2026 (the splice manager's composer) — the figures a status-line post carries, as numbers instead of an
// ANSI line: the model the line names, the reasoning effort, how full the context is and how much of it the cache
// served. StatuslineRenderer draws these same facts for the terminal; this is the same reading for SessionLines, which
// the console's composer asks. The row lens (StatuslineRow) and the effort reader (StatuslineBars) are the renderer's
// own, so the console shows the figure the status line shows, units included.
package splice.usage.statusline

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.model.ModelCatalog
import splice.core.session.SessionContext
import splice.core.session.SessionLine
import splice.core.util.JsonScalars

internal class StatuslineFacts(catalog: ModelCatalog?) {
    private val row = StatuslineRow(catalog)
    private val bars = StatuslineBars()

    /** The post [root]'s facts. [unanswered] is StatuslineRenderer's: this head has not answered the session since it
     *  took it over, so a usage the post carries is another head's last turn and is not read as this one's context. */
    fun read(root: JsonObject, unanswered: Boolean): SessionLine {
        val model = root["model"] as? JsonObject
        val id = text(model?.get("id"))
        val name = row.label(id) ?: text(model?.get("display_name")) ?: id
        return SessionLine(id, name, bars.effort(root), context(root, id, unanswered))
    }

    private fun context(root: JsonObject, id: String?, unanswered: Boolean): SessionContext? {
        val window = (root["context_window"] as? JsonObject)
            ?.takeUnless { unanswered && it[CURRENT_USAGE] is JsonObject }
            ?: return null
        val usage = window[CURRENT_USAGE] as? JsonObject
        val read = number(usage?.get("cache_read_input_tokens"))
        val input = number(usage?.get("input_tokens")) + read + number(usage?.get("cache_creation_input_tokens"))
        val reported = if (usage == null) number(window["total_input_tokens"]) else input
        val (size, used) = row.window(id, number(window["context_window_size"]), reported)
        val ratio = if (size > 0) (used * STATUSLINE_PERCENT_SCALE / size).toInt() else 0
        val cache = if (input > 0) (read * STATUSLINE_PERCENT_SCALE / input).toInt() else null
        return SessionContext(used, size, figure(window["used_percentage"])?.toInt() ?: ratio, cache)
    }

    private fun text(element: JsonElement?): String? = JsonScalars.str(element)?.takeIf { it.isNotEmpty() }

    private fun figure(element: JsonElement?): Long? = JsonScalars.str(element)?.toDoubleOrNull()?.toLong()

    private fun number(element: JsonElement?): Long = figure(element) ?: 0
}

private const val CURRENT_USAGE = "current_usage"

// why: the status line reports context and cache ratios as whole percentages, not fractions.
private const val STATUSLINE_PERCENT_SCALE = 100

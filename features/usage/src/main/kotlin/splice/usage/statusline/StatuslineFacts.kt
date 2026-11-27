package splice.usage.statusline

import kotlinx.serialization.json.JsonObject
import splice.core.model.ClientUsage
import splice.core.model.ModelCatalog
import splice.core.session.SessionLine
import splice.core.util.JsonScalars

/** The same context figures and model name as the terminal, supplied to the console's composer. */
internal class StatuslineFacts(catalog: ModelCatalog?, replies: ClientUsage?) {
    private val row = StatuslineRow(catalog)
    private val bars = StatuslineBars()
    private val context = StatuslineContextRead(replies)

    fun read(root: JsonObject, unanswered: Boolean): SessionLine {
        val model = root["model"] as? JsonObject
        val id = JsonScalars.str(model?.get("id"))?.takeIf { it.isNotEmpty() }
        val name = row.label(id) ?: JsonScalars.str(model?.get("display_name"))?.takeIf { it.isNotEmpty() } ?: id
        return SessionLine(id, name, bars.effort(root), context.read(root, unanswered))
    }
}

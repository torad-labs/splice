package splice.usage.statusline

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.model.ClientInput
import splice.core.model.ClientUsage
import splice.core.session.SessionContext
import splice.core.util.JsonScalars

/** Read the exact input behind a matching reply. Without a match, keep the client's figures. */
internal class StatuslineContextRead(private val replies: ClientUsage?) {
    fun read(root: JsonObject, unanswered: Boolean): SessionContext? {
        val window = root["context_window"] as? JsonObject ?: return null
        val usage = window["current_usage"] as? JsonObject
        // A head that just took over the session has not supplied these figures yet.
        if (unanswered && usage != null) return null
        val reported = usage?.let(::input)
        val actual = reported?.let { replies?.context(JsonScalars.str(root["session_id"]), it) }
        val cache = reported?.let { percent(it.cached, it.total) }
        return if (actual != null) {
            SessionContext(actual.input, actual.window, percent(actual.input, actual.window) ?: 0, cache)
        } else {
            posted(window, reported, cache)
        }
    }

    private fun posted(window: JsonObject, reported: ClientInput?, cache: Int?): SessionContext {
        val used = reported?.total ?: number(window["total_input_tokens"]) ?: 0
        val size = number(window["context_window_size"]) ?: 0
        val shown = number(window["used_percentage"])?.toInt() ?: percent(used, size) ?: 0
        return SessionContext(used, size, shown, cache)
    }

    private fun input(usage: JsonObject): ClientInput = ClientInput(
        number(usage["input_tokens"]) ?: 0,
        number(usage["cache_read_input_tokens"]) ?: 0,
        number(usage["cache_creation_input_tokens"]) ?: 0,
    )

    private fun number(element: JsonElement?): Long? = JsonScalars.str(element)?.toDoubleOrNull()?.toLong()

    private fun percent(amount: Long, size: Long): Int? =
        if (size > 0) (amount * PERCENT_SCALE / size).toInt() else null
}

// why: report context and cache shares as whole percentages, not fractions.
private const val PERCENT_SCALE = 100

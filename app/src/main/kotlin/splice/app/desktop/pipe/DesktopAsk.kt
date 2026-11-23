// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 5). What a line Claude Desktop writes to a Code
// tab session asks of the pipe: the `initialize` it keeps to replay to the next program, the `set_model` that may
// switch the program, or nothing of the pipe's (every other line, which it only passes on).
package splice.app.desktop.pipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** What one of Desktop's lines asks of the pipe. */
internal sealed class DesktopAsk {
    /** The session's `initialize` request, replayed to every program a switch starts. */
    data object Initialize : DesktopAsk()

    /** A request to run the session on [model] from now on. */
    data class SetModel(val model: String) : DesktopAsk()

    /** Anything else, the program's alone. */
    data object Other : DesktopAsk()
}

/** Reads what a Desktop line asks of the pipe. */
internal class DesktopAskReader {
    fun of(line: String): DesktopAsk {
        val message = JsonScalars.objectOrNull(Json, line) ?: return DesktopAsk.Other
        if (JsonScalars.str(message, "type") != CONTROL_REQUEST) return DesktopAsk.Other
        val request = message["request"] as? JsonObject
        return when (JsonScalars.str(request, "subtype")) {
            "initialize" -> DesktopAsk.Initialize
            "set_model" -> JsonScalars.str(request, "model")?.takeIf(String::isNotBlank)?.let(DesktopAsk::SetModel)
                ?: DesktopAsk.Other
            else -> DesktopAsk.Other
        }
    }
}

private const val CONTROL_REQUEST = "control_request"

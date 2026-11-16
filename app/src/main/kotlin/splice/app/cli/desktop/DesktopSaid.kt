// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). `splice desktop` in words: what the daemon says of the
// door, a line for each thing a person asks of it.
package splice.app.cli.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars

/** `splice desktop` in words. */
internal class DesktopSaid(private val out: TerminalOutput) {
    fun state(state: JsonObject) {
        val on = JsonScalars.str(state, "on") == "true"
        out.line("Desktop: ${if (on) "on" else "off"}")
        out.line("Door: ${JsonScalars.str(state, "door").orEmpty()}")
        out.line("Default model: ${JsonScalars.str(state, "default_model") ?: "none: no head is in the door"}")
        val models = (state["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        out.line(if (models.isEmpty()) "Models: none" else "Models:")
        models.forEach { m ->
            out.line("  ${text(m, "model")}  ${text(m, "label")}  (${text(m, "command")})")
        }
        val left = (state["left_out"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (left.isNotEmpty()) out.line("Left out:")
        left.forEach { h ->
            out.line("  ${text(h, "command")} (${text(h, "head")}): ${text(h, "reason")}")
        }
        val last = state["last_turn"] as? JsonObject
        if (last == null) {
            out.line("Last turn: none yet")
        } else {
            val asked = JsonScalars.str(last, "model_asked")
            val ran = JsonScalars.str(last, "model")
            val session = JsonScalars.str(last, "session_id")?.let { " in session $it" }.orEmpty()
            out.line("Last turn: ${text(last, "at")} on ${text(last, "head")}$session, asked $asked, ran $ran")
        }
    }

    private fun text(from: JsonObject, key: String): String = JsonScalars.str(from, key).orEmpty()
}

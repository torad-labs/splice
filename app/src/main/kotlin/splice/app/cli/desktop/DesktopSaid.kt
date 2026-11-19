// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). `splice desktop` in words: what the daemon says of the
// door, a line for each thing a person asks of it.
package splice.app.cli.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.control.DESKTOP_ENV
import splice.client.desktop.DESKTOP_ENV_PLACE
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars

/** `splice desktop` in words. */
internal class DesktopSaid(private val out: TerminalOutput) : DoorSaid {
    override fun state(state: JsonObject) {
        val on = JsonScalars.str(state, "on") == "true"
        out.line("Desktop: ${if (on) "on" else "off"}")
        out.line("Door: ${JsonScalars.str(state, "door").orEmpty()}")
        val starts = JsonScalars.str(state, "default_model")?.let { "$it, the model `claude` starts on" }
        out.line("Default model: ${starts ?: "none: no head is in the door"}")
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
            out.line("Last turn sent: none yet")
        } else {
            val asked = JsonScalars.str(last, "model_asked")
            val ran = JsonScalars.str(last, "model")
            val session = JsonScalars.str(last, "session_id")?.let { " in session $it" }.orEmpty()
            out.line("Last turn sent: ${text(last, "at")} on ${text(last, "head")}$session, asked $asked, sent as $ran")
        }
    }

    /** What to set in Desktop's environment store, when the act's answer carries it: one `NAME=value` line each. */
    override fun acted(state: JsonObject) {
        val env = (state[DESKTOP_ENV] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (env.isEmpty()) return
        out.line("splice: set these in $DESKTOP_ENV_PLACE, then start a new Code tab session:")
        env.forEach { out.line("  ${text(it, "name")}=${text(it, "value")}") }
        out.line("splice: they stay set until you remove them there; `splice desktop off` says which.")
    }

    private fun text(from: JsonObject, key: String): String = JsonScalars.str(from, key).orEmpty()
}

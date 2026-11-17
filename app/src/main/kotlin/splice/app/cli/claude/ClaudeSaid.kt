// NEW: door-default (docs/specs/door-default.md, decisions 1 and 4). `splice claude` in words: the lane, and on the
// door what a new session meets there.
package splice.app.cli.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.cli.desktop.DoorSaid
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.launch.recipe.HEADS_DEPRECATED

/** `splice claude` in words. */
internal class ClaudeSaid(private val out: TerminalOutput) : DoorSaid {
    override fun state(state: JsonObject) {
        if (JsonScalars.str(state, "lane") != "door") {
            out.line("claude: on its head; $HEADS_DEPRECATED")
            return
        }
        out.line("claude: on the door, at ${text(state, "door")}")
        val starts = JsonScalars.str(state, "default_model")
        out.line(if (starts == null) "Starts on: no model, as no head is in the door" else "Starts on: $starts")
        val models = (state["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        out.line(if (models.isEmpty()) NO_PICKER else "Picker:")
        models.forEach { m ->
            out.line("  ${text(m, "model")}  ${text(m, "label")}  (${text(m, "command")})")
        }
        val left = (state["left_out"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (left.isNotEmpty()) out.line("Left out:")
        left.forEach { h ->
            out.line("  ${text(h, "command")} (${text(h, "head")}): ${text(h, "reason")}")
        }
        val own = JsonScalars.str(state, "own_login")
        out.line(if (own == null) SHARED_LOGIN else "/login: $THEIRS_IN_THE_WAY $own, so splice's is not laid")
    }

    private fun text(from: JsonObject, key: String): String = JsonScalars.str(from, key).orEmpty()
}

// What the /login line says when the person's own is in the way of splice's, before where it is.
private const val THEIRS_IN_THE_WAY = "yours, not splice's accounts page: your own /login is in the way at"

// What the picker line says with no account signed in.
private const val NO_PICKER =
    "Picker: Claude Code's own models, as no account is signed in; /login in a session opens splice's accounts"

// What the /login line says while splice's is laid: it is in the person's own Claude folder, so a Claude Code splice did
// not start runs it too, for its own /login row as well (Claude Code 2.1.326).
private const val SHARED_LOGIN = "/login: splice's, in every claude on this machine, even one splice did not start; " +
    "`splice console` opens splice's accounts, `claude auth login` is Claude Code's own sign-in"

// NEW: head-catalog (gw-retire). `splice claude retire` and `splice claude restore`: custom heads are deprecated, and a
// person retires them now with one command, per head or all at once, and brings any back exactly as it was with the
// other. The daemon holds the heads, their live sessions and the lane, so both verbs ask it (HeadRetirement) and say
// what it answered, a line per thing done, refused or left; the exit status is 1 when any head named stayed as it was.
//
//   splice claude retire <command|key>... | --all
//   splice claude restore <command|key>... | --all
package splice.app.cli.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.cli.desktop.DoorClient
import splice.app.cli.desktop.UNREADABLE
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars

internal class ClaudeRetire(private val client: DoorClient, private val out: TerminalOutput) {
    /** [verb], `retire` or `restore`, on the heads [rest] names, or on every one with `--all`. */
    fun run(verb: String, rest: List<String>): Int {
        val all = rest == listOf(RETIRE_ALL)
        val named = rest.isNotEmpty() && rest.none { it.startsWith("-") }
        if (all || named) return asked(verb, rest, all)
        return client.refused("usage: splice claude $verb <command|key>... | $RETIRE_ALL")
    }

    private fun asked(verb: String, rest: List<String>, all: Boolean): Int {
        val body = buildJsonObject {
            if (all) put("all", true) else putJsonArray("heads") { rest.forEach { add(JsonPrimitive(it)) } }
        }
        val reply = client.posted(verb, body.toString()) ?: return 1
        val answer = client.parsed(reply) ?: return client.refused(UNREADABLE)
        (answer["said"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str).forEach { out.line("splice: $it") }
        return if (JsonScalars.str(answer, "ok") == "true") 0 else 1
    }
}

private const val RETIRE_ALL = "--all"

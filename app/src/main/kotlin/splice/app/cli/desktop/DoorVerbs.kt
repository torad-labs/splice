// NEW: door-default (docs/specs/door-default.md, decision 1; desktop-door.md, decision 4). The shape `splice desktop`
// and `splice claude` share: the bare command (or `--json`) reads what the daemon says of the door and says it, one
// verb opens the door to its caller, alone or with `--model <selector>`, and says where a session starts, and the
// other verbs act and say what changed. Each command names only its verbs and how its answers are said (DoorSaid).
package splice.app.cli.desktop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars

/** How one command says the daemon's answers in words. */
internal interface DoorSaid {
    /** What `GET /api/<area>` answered. */
    fun state(state: JsonObject)

    /** What an act answered beyond what changed and where a session starts; nothing by default. */
    fun acted(state: JsonObject) = Unit
}

/** `splice <area>`'s verbs on [client]: [opens] the one that takes `--model`, [plain] the ones that take nothing,
 *  [usage] what they are, said on a wrong ask. */
internal class DoorVerbs(
    private val client: DoorClient,
    private val out: TerminalOutput,
    private val said: DoorSaid,
    private val opens: String,
    private val plain: Set<String>,
    private val usage: String,
) {
    fun run(args: List<String>): Int = when {
        args.isEmpty() || args == listOf("--json") -> asked(json = args.isNotEmpty())
        args.first() == opens -> opened(args.drop(1))
        args.singleOrNull() in plain -> acted(args.first(), NO_BODY)
        else -> refused()
    }

    /** Validate an opening command before its caller asks permission or installs a prerequisite. */
    fun canOpen(args: List<String>): Boolean = args.firstOrNull() == opens && openingBody(args.drop(1)) != null

    private fun opened(rest: List<String>): Int = openingBody(rest)?.let { acted(opens, it) } ?: refused()

    private fun openingBody(rest: List<String>): String? = when {
        rest.isEmpty() -> NO_BODY
        rest.size == 2 && rest.first() == "--model" -> buildJsonObject { put("model", rest.last()) }.toString()
        else -> null
    }

    /** What the daemon says of the door, as data or in words. */
    private fun asked(json: Boolean): Int {
        val reply = client.read() ?: return 1
        val state = client.parsed(reply)
        when {
            json -> out.line(reply.body)
            state == null -> return client.refused(UNREADABLE)
            else -> said.state(state)
        }
        return 0
    }

    /** One act, said in words: what changed, where a session starts when it opened the door, and the rest. */
    private fun acted(verb: String, body: String): Int {
        val reply = client.posted(verb, body) ?: return 1
        val state = client.parsed(reply) ?: return client.refused(UNREADABLE)
        JsonScalars.str(state, "said")?.let { out.line("splice: $it.") }
        if (verb == opens) JsonScalars.str(state, "default_from")?.let { out.line("splice: a session starts on $it.") }
        said.acted(state)
        return 0
    }

    private fun refused(): Int = client.refused("usage: $usage")
}

/** What an act with nothing to say sends. */
internal const val NO_BODY = "{}"

/** What a command says when the daemon's answer is not JSON. */
internal const val UNREADABLE = "the daemon's answer could not be read"

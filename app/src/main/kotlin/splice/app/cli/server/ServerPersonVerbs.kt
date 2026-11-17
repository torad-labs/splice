// NEW: server mode (docs/specs/server-mode.md, F3, F4, F5 and F12): `splice server person set <email> [--inflight N]
// [--rpm N] [--tpm N] [--upstream NAME] [--team NAME] [--commands a,b|all]`, what the operator sets for one person, from
// that person's next turn: their own caps, which hold over `[server.limits]` (0 is no in-flight or per-minute cap; a
// token cap is above 0), the upstream their turns go to while it is healthy (`--upstream ""` takes the preference
// back), the team their usage is reported under from then on (`--team ""` leaves them on their email domain's team),
// and the commands they may use, over their team's. Which numbers, names and commands hold is the daemon's to say.
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk

internal class ServerPersonVerbs(private val ask: PeerAsk) {
    private val lines = ServerLines()
    private val flags = CAPS + NAMES + COMMANDS_FLAG

    /** `person set <email> [--inflight N] [--rpm N] [--tpm N] [--upstream NAME] [--team NAME] [--commands a,b|all]`,
     *  with at least one of them. */
    fun set(args: List<String>): Int? {
        val parsed = PeerArgsParser(flags, emptySet()).parse(args)
        val person = parsed?.words?.singleOrNull()
        val body = parsed?.let { settingsOf(it.values) }
        if (person == null || body == null) return null
        return ask.managed(ControlRequest("PUT", "$PEOPLE_PATH/${ask.segment(person)}", body)) { lines.person(it) }
    }

    /** `{"inflight": N, "rpm": N, "tpm": N, "upstream": name, "team": name, "commands": ...}` from the flags given;
     *  null when no flag is given or a cap is no whole number. */
    private fun settingsOf(given: Map<String, String>): JsonObject? {
        val caps = given.filterKeys { it in CAPS }.mapValues { (_, text) -> text.trim().toIntOrNull() }
        if (given.isEmpty() || caps.values.any { it == null }) return null
        return buildJsonObject {
            caps.forEach { (flag, cap) -> put(flag.removePrefix("--"), cap) }
            NAMES.forEach { flag -> given[flag]?.let { put(flag.removePrefix("--"), it) } }
            given[COMMANDS_FLAG]?.let { put(COMMANDS_FIELD, CommandsFlag().personal(it)) }
        }
    }
}

private const val UPSTREAM_FLAG = "--upstream"
internal const val TEAM_FLAG = "--team"

// The flags that take a whole number, a cap.
private val CAPS = setOf("--inflight", "--rpm", "--tpm")

// The flags that take a name rather than a cap.
private val NAMES = setOf(UPSTREAM_FLAG, TEAM_FLAG)

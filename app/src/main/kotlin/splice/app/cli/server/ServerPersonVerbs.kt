// NEW: server mode (docs/specs/server-mode.md, F3 and F4): `splice server person set <email> [--inflight N] [--rpm N]
// [--upstream NAME]`, what the operator sets for one person, from that person's next turn: their own caps, which hold
// over `[server.limits]` (0 is no cap), and the upstream their turns go to while it is healthy (`--upstream ""` takes
// the preference back). The same verb later carries a person's team.
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk

internal class ServerPersonVerbs(private val ask: PeerAsk) {
    private val lines = ServerLines()

    /** `person set <email> [--inflight N] [--rpm N] [--upstream NAME]`, with at least one of the three. */
    fun set(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(INFLIGHT_FLAG, RPM_FLAG, UPSTREAM_FLAG), emptySet()).parse(args)
        val person = parsed?.words?.singleOrNull()
        val body = parsed?.let { settingsOf(it.values) }
        if (person == null || body == null) return null
        return ask.managed(ControlRequest("PUT", "$PEOPLE_PATH/${ask.segment(person)}", body)) { lines.person(it) }
    }

    /** `{"inflight": N, "rpm": N, "upstream": name}` from the flags given, each cap 0 or more; null when no flag is
     *  given or a cap is no such number. Whether the name is an upstream is the daemon's to say: it holds them. */
    private fun settingsOf(flags: Map<String, String>): JsonObject? {
        val caps = (flags - UPSTREAM_FLAG).mapValues { (_, text) -> text.toIntOrNull()?.takeIf { it >= 0 } }
        if (flags.isEmpty() || caps.values.any { it == null }) return null
        return buildJsonObject {
            caps.forEach { (flag, cap) -> put(flag.removePrefix("--"), cap) }
            flags[UPSTREAM_FLAG]?.let { put(UPSTREAM_FLAG.removePrefix("--"), it) }
        }
    }
}

private const val INFLIGHT_FLAG = "--inflight"
private const val RPM_FLAG = "--rpm"
private const val UPSTREAM_FLAG = "--upstream"

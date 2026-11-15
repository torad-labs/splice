// NEW: server mode (docs/specs/server-mode.md, F3): `splice server person set <email> [--inflight N] [--rpm N]`, the
// operator's own caps for one person, which hold over `[server.limits]` from that person's next turn. 0 is no cap. The
// same verb later carries a person's team and upstream.
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerLine

internal class ServerPersonVerbs(private val ask: ServerAsk) {
    private val lines = ServerLines()

    /** `person set <email> [--inflight N] [--rpm N]`, with at least one of the two. */
    fun set(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(INFLIGHT_FLAG, RPM_FLAG), emptySet()).parse(args)
        val person = parsed?.words?.singleOrNull()
        val body = parsed?.let { capsOf(it.values) }
        if (person == null || body == null) return null
        return ask.send(ServerCall("PUT", "$PEOPLE_PATH/${ask.segment(person)}", body), PeerLine(lines::person))
    }

    /** `{"inflight": N, "rpm": N}` from the flags given, each 0 or more; null when none is given or one is no such
     *  number. */
    private fun capsOf(flags: Map<String, String>): JsonObject? {
        val caps = flags.mapValues { (_, text) -> text.toIntOrNull()?.takeIf { it >= 0 } }
        if (caps.isEmpty() || caps.values.any { it == null }) return null
        return buildJsonObject { caps.forEach { (flag, cap) -> put(flag.removePrefix("--"), cap) } }
    }
}

private const val INFLIGHT_FLAG = "--inflight"
private const val RPM_FLAG = "--rpm"

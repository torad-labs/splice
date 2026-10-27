// NEW: Oct 27, 2026 (docs/peers.md, "Send as the peer"): `splice peer limit <name> <per-minute> <burst>` gives a peer a
// send limit of its own, and `splice peer limit <name> --default` gives it the default back (20 at once, 60 a minute).
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import java.net.URLEncoder

internal class PeerLimitCommand(private val call: PeerCall, private val out: TerminalOutput) {

    fun run(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf("--default")).parse(args)
        val name = parsed?.words?.firstOrNull()
        val body = parsed?.let { bodyOf(it) }
        if (name == null || body == null) return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val path = "/api/peers/${URLEncoder.encode(name, Charsets.UTF_8)}/limit"
        return when (val answer = call.call("PUT", path, key, body)) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                out.line(shown(answer.body))
                PeerExit.DONE
            }
        }
    }

    /** `{"default": true}` for `<name> --default`, the two numbers for `<name> <per-minute> <burst>`, else null. */
    private fun bodyOf(parsed: PeerArgs): JsonObject? {
        val reset = "--default" in parsed.switches
        val words = parsed.words
        return when {
            reset && words.size == 1 -> buildJsonObject { put("default", true) }
            !reset && words.size == SET_WORDS -> numbers(words[1], words[2])
            else -> null
        }
    }

    private fun numbers(perMinute: String, burst: String): JsonObject? {
        val rate = perMinute.toIntOrNull()
        val held = burst.toIntOrNull()
        return if (rate == null || held == null) {
            null
        } else {
            buildJsonObject {
                put("perMinute", rate)
                put("burst", held)
            }
        }
    }

    private fun shown(reply: JsonObject): String {
        val limit = reply["limit"] as? JsonObject
        val rate = limit?.let { JsonScalars.long(it, "perMinute") }
        val burst = limit?.let { JsonScalars.long(it, "burst") }
        return "${JsonScalars.str(reply, "name")} sends $rate a minute, $burst at once"
    }
}

// why: `limit <name> <per-minute> <burst>` is three words.
private const val SET_WORDS = 3

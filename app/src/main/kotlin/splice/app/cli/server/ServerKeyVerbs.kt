// NEW: server mode (docs/specs/server-mode.md, F1): `splice server key create|list|revoke|revoke-person`. A key is
// printed once, by `create`, and by nothing after it: the daemon keeps its hash, and `list` shows its prefix.
package splice.app.cli.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.JSON_SWITCH
import splice.app.cli.peer.PeerAnswer
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerExit
import splice.app.cli.peer.PeerLine
import splice.core.terminal.TerminalOutput
import java.net.URLEncoder

internal class ServerKeyVerbs(private val call: PeerCall, private val out: TerminalOutput) {
    private val lines = ServerLines()

    /** `key create --person <email> [--name <name>] [--expires <duration>] [--json]` */
    fun create(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(PERSON_FLAG, NAME_FLAG, EXPIRES_FLAG), setOf(JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() && PERSON_FLAG in it.values } ?: return null
        val body = buildJsonObject {
            parsed.values.forEach { (flag, value) -> put(flag.removePrefix("--"), value) }
        }
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val answer = call.call("POST", KEYS_PATH, key, body)
        return finish(answer, JSON_SWITCH in parsed.switches, PeerLine(lines::minted))
    }

    /** `key list [--person <email>] [--json]` */
    fun list(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(PERSON_FLAG), setOf(JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() } ?: return null
        val query = parsed.values[PERSON_FLAG]?.let { "?person=${segment(it)}" }.orEmpty()
        val key = call.managementKey() ?: return PeerExit.REFUSED
        return finish(call.call("GET", KEYS_PATH + query, key), JSON_SWITCH in parsed.switches, PeerLine(lines::keys))
    }

    /** `key revoke <prefix>` */
    fun revoke(args: List<String>): Int? {
        val prefix = args.singleOrNull()?.takeUnless { it.startsWith("--") } ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        return finish(call.call("DELETE", "$KEYS_PATH/${segment(prefix)}", key), false, PeerLine(lines::revoked))
    }

    /** `key revoke-person <email>`: offboarding, every key of one person at once. */
    fun revokePerson(args: List<String>): Int? {
        val person = args.singleOrNull()?.takeUnless { it.startsWith("--") } ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val answer = call.call("DELETE", "$PEOPLE_PATH/${segment(person)}/keys", key)
        return finish(answer, false, PeerLine(lines::revokedPerson))
    }

    /** Prints a 2xx's body, whole for `--json` and as [line] reads it otherwise, and answers 0; otherwise the exit
     *  code the call already chose, its reason printed. */
    private fun finish(answer: PeerAnswer, json: Boolean, line: PeerLine): Int = when (answer) {
        is PeerAnswer.Exit -> answer.code
        is PeerAnswer.Ok -> {
            out.line(if (json) answer.body.toString() else line(answer.body).orEmpty())
            PeerExit.DONE
        }
    }

    private fun segment(word: String): String = URLEncoder.encode(word, Charsets.UTF_8)
}

internal const val KEYS_PATH = "/api/server/keys"
internal const val PEOPLE_PATH = "/api/server/people"
private const val PERSON_FLAG = "--person"
private const val NAME_FLAG = "--name"
private const val EXPIRES_FLAG = "--expires"

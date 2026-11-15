// NEW: server mode (docs/specs/server-mode.md, F1): `splice server key create|list|revoke|revoke-person`. A key is
// printed once, by `create`, and by nothing after it: the daemon keeps its hash, and `list` shows its prefix.
package splice.app.cli.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.JSON_SWITCH
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerLine

internal class ServerKeyVerbs(private val ask: ServerAsk) {
    private val lines = ServerLines()

    /** `key create --person <email> [--name <name>] [--expires <duration>] [--json]` */
    fun create(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(PERSON_FLAG, NAME_FLAG, EXPIRES_FLAG), setOf(JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() && PERSON_FLAG in it.values } ?: return null
        val body = buildJsonObject {
            parsed.values.forEach { (flag, value) -> put(flag.removePrefix("--"), value) }
        }
        val minted = ServerCall("POST", KEYS_PATH, body)
        return ask.send(minted, PeerLine(lines::minted), json = JSON_SWITCH in parsed.switches)
    }

    /** `key list [--person <email>] [--json]` */
    fun list(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf(PERSON_FLAG), setOf(JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() } ?: return null
        val query = parsed.values[PERSON_FLAG]?.let { "?person=${ask.segment(it)}" }.orEmpty()
        val listed = ServerCall("GET", KEYS_PATH + query)
        return ask.send(listed, PeerLine(lines::keys), json = JSON_SWITCH in parsed.switches)
    }

    /** `key revoke <prefix>` */
    fun revoke(args: List<String>): Int? {
        val prefix = word(args) ?: return null
        return ask.send(ServerCall("DELETE", "$KEYS_PATH/${ask.segment(prefix)}"), PeerLine(lines::revoked))
    }

    /** `key revoke-person <email>`: offboarding, every key of one person at once. */
    fun revokePerson(args: List<String>): Int? {
        val person = word(args) ?: return null
        val revoked = ServerCall("DELETE", "$PEOPLE_PATH/${ask.segment(person)}/keys")
        return ask.send(revoked, PeerLine(lines::revokedPerson))
    }

    /** The one word a verb takes after its name; null for none, more than one, or a flag. */
    private fun word(args: List<String>): String? = args.singleOrNull()?.takeUnless { it.startsWith("--") }
}

internal const val KEYS_PATH = "/api/server/keys"
internal const val PEOPLE_PATH = "/api/server/people"
private const val PERSON_FLAG = "--person"
private const val NAME_FLAG = "--name"
private const val EXPIRES_FLAG = "--expires"

// NEW: server mode (docs/specs/server-mode.md, F1, F3 and F4): `splice server`, the operator's verbs on a team's keys
// and people. Every verb is a call on the daemon's control plane with the management key, since the daemon holds the keys
// the listener answers: a key minted or revoked here is the listener's on its very next request.
package splice.app.cli.server

import splice.app.cli.peer.PeerAsk
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerExit
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal const val SERVER_USAGE =
    "key create --person <email> [--name <name>] [--expires <duration>] [--json] | " +
        "key list [--person <email>] [--json] | key revoke <prefix> | key revoke-person <email> | " +
        "person set <email> [--inflight N] [--rpm N] [--upstream NAME]"

/** `splice server <key|person> <verb>`. [out] is stdout, [err] stderr, so `--json | jq` never reads a refusal. */
internal class ServerCommand(
    out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    env: EnvReader = EnvReader(System::getenv),
) {
    private val ask = PeerAsk(PeerCall(env, err, "server"), out)
    private val keys = ServerKeyVerbs(ask)
    private val people = ServerPersonVerbs(ask)

    fun run(args: List<String>): Int {
        val rest = args.drop(2)
        val code = when (args.take(2).joinToString(" ")) {
            "key create" -> keys.create(rest)
            "key list" -> keys.list(rest)
            "key revoke" -> keys.revoke(rest)
            "key revoke-person" -> keys.revokePerson(rest)
            "person set" -> people.set(rest)
            else -> null
        }
        if (code == null) err.line("usage: splice server $SERVER_USAGE")
        return code ?: PeerExit.BAD_USAGE
    }
}

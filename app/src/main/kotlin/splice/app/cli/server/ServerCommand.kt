// NEW: server mode (docs/specs/server-mode.md, F1, F3, F4 and F5): `splice server`, the operator's verbs on a team's
// keys, people, usage and budgets. Every verb is a call on the daemon's control plane with the management key, since
// the daemon holds what the listener answers by: a key minted or revoked here is the listener's on its next request.
package splice.app.cli.server

import splice.app.cli.peer.PeerAsk
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerExit
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal const val SERVER_USAGE =
    "key create --person <email> [--name <name>] [--expires <duration>] [--json] | " +
        "key list [--person <email>] [--json] | key revoke <prefix> | key revoke-person <email> | " +
        "person set <email> [--inflight N] [--rpm N] [--upstream NAME] [--team NAME] | " +
        "usage rows [--since D | --from T --to T] [--person <email>] [--json] | usage export --now | " +
        "usage report [--by person|team|current-team|day|model|device] [--since D | --from T --to T] " +
        "[--format json|csv|md] | budget set --person <email>|--team <name> [--daily-usd N] [--monthly-usd N] " +
        "[--action block|warn] | budget list [--json]"

/** One verb: its exit code for the words after its name, or null when they are no use of it. */
internal fun interface ServerVerb {
    fun run(args: List<String>): Int?
}

/** `splice server <key|person> <verb>`. [out] is stdout, [err] stderr, so `--json | jq` never reads a refusal. */
internal class ServerCommand(
    out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    env: EnvReader = EnvReader(System::getenv),
) {
    private val ask = PeerAsk(PeerCall(env, err, "server"), out)
    private val keys = ServerKeyVerbs(ask)
    private val people = ServerPersonVerbs(ask)
    private val usage = ServerUsageVerbs(ask)
    private val budgets = ServerBudgetVerbs(ask)

    // Each verb by the two words that name it.
    private val verbs: Map<String, ServerVerb> = mapOf(
        "key create" to ServerVerb(keys::create),
        "key list" to ServerVerb(keys::list),
        "key revoke" to ServerVerb(keys::revoke),
        "key revoke-person" to ServerVerb(keys::revokePerson),
        "person set" to ServerVerb(people::set),
        "usage rows" to ServerVerb(usage::rows),
        "usage report" to ServerVerb(usage::report),
        "usage export" to ServerVerb(usage::export),
        "budget set" to ServerVerb(budgets::set),
        "budget list" to ServerVerb(budgets::list),
    )

    fun run(args: List<String>): Int {
        val code = verbs[args.take(2).joinToString(" ")]?.run(args.drop(2))
        if (code == null) err.line("usage: splice server $SERVER_USAGE")
        return code ?: PeerExit.BAD_USAGE
    }
}

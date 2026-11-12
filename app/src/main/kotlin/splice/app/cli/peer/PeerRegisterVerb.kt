// NEW: Oct 28, 2026, out of PeerCommand.kt by concern (Marcos: "add the ability for anyone or anything to register to
// send and receive messages via splice peer"): `splice peer register <name> [--cwd DIR] [--send-to A,B] [--owner PID]`.
package splice.app.cli.peer

import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars

/** `splice peer register`, which presents the management key. Null on a bad argv, for the usage line. */
internal class PeerRegisterVerb(
    private val call: PeerCall,
    private val out: TerminalOutput,
    private val err: TerminalOutput,
) {
    fun run(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--cwd", "--send-to", "--owner"), emptySet()).parse(args)
        val name = parsed?.words?.singleOrNull() ?: return null
        val body = PeerRegistrationBody.build(name, parsed.values) ?: return notRunning(parsed.values["--owner"])
        return call.managementKey()?.let { registered(call.call("POST", "/api/peers", it, body)) } ?: PeerExit.REFUSED
    }

    /** `--owner` named a pid no running process has. */
    private fun notRunning(pid: String?): Int {
        err.line("--owner $pid is not a running process")
        return PeerExit.BAD_USAGE
    }

    private fun registered(answer: PeerAnswer): Int = when (answer) {
        is PeerAnswer.Exit -> answer.code
        is PeerAnswer.Ok -> {
            val reply = answer.body
            out.line("registered ${JsonScalars.str(reply, "name")}")
            out.line("  session id  ${JsonScalars.str(reply, "sessionId")}")
            out.line("  key file    ${JsonScalars.str(reply, "keyFile")}")
            out.line("  socket      ${JsonScalars.str(reply, "socket") ?: "not yet: the peer is still starting"}")
            PeerExit.DONE
        }
    }
}

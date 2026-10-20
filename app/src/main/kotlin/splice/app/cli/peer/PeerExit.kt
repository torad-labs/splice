// NEW: Oct 19, 2026, moved verbatim out of PeerCall.kt by concern (ConcentrationLawTest): what a `splice peer` call
// comes to, and the exit code each outcome leaves.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject

/** The exit codes of `splice peer` (docs/specs/peer-messaging.md 5); 0, 1 and 2 keep their meaning across the CLI. */
internal object PeerExit {
    // why: success is 0 for every splice verb, so a script can test `splice peer send` like any command.
    const val DONE: Int = 0

    // why: 1 is the CLI's refusal: the daemon said no, or the key file could not be read.
    const val REFUSED: Int = 1

    // why: 2 is the CLI's usage error, as every other splice verb answers a bad argv.
    const val BAD_USAGE: Int = 2

    // why: spec 5, 3 says no daemon answered at all, so a caller can start one and retry.
    const val UNREACHABLE: Int = 3

    // why: spec 5, 4 is the send rate's 429, so a caller backs off rather than giving up.
    const val RATE_LIMITED: Int = 4

    // why: spec 5, 5 is a 502 or 503: the helper or the target's socket failed, and a later try may pass.
    const val TRANSPORT: Int = 5
}

/** What a call came to: the daemon's 2xx body, or the exit code with the reason already printed. */
internal sealed class PeerAnswer {
    data class Ok(val body: JsonObject) : PeerAnswer()

    data class Exit(val code: Int) : PeerAnswer()
}

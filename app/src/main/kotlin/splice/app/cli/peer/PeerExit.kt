// NEW: Oct 19, 2026, moved verbatim out of PeerCall.kt by concern (ConcentrationLawTest): what a `splice peer` call
// comes to, and the exit code each outcome leaves.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject

/** The exit codes of `splice peer` (docs/specs/peer-messaging.md 5); 0, 1 and 2 keep their meaning across the CLI. */
internal object PeerExit {
    const val DONE: Int = 0
    const val REFUSED: Int = 1
    const val USAGE: Int = 2
    const val UNREACHABLE: Int = 3
    const val RATE_LIMITED: Int = 4
    const val TRANSPORT: Int = 5
}

/** What a call came to: the daemon's 2xx body, or the exit code with the reason already printed. */
internal sealed class PeerAnswer {
    data class Ok(val body: JsonObject) : PeerAnswer()

    data class Exit(val code: Int) : PeerAnswer()
}

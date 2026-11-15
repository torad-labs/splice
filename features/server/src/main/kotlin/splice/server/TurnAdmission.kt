// NEW: server mode (docs/specs/server-mode.md, F3 and F4). What a turn passes before it is relayed, on either route
// the listener serves one on: the Messages route Claude Code speaks, and the chat completions an OpenAI-style client
// asks. The caps are a person's, not a route's, so both take their slots here, and each refuses in its own client's
// error shape (decision 6): a turn whose provider has no server up is a 503 naming its upstreams, which counts toward
// no cap, and a turn past a cap is a 429 with `Retry-After`.
package splice.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.wire.HttpStatus
import splice.share.RefusalVoice
import splice.share.SharedHead

internal class TurnAdmission(private val parts: ServerParts, private val voice: RefusalVoice) {
    /** The slot a turn on [head] takes among those of [key]'s person, or null with the turn refused: one past a cap,
     *  and before that one whose provider has no server up. */
    suspend fun slot(call: ApplicationCall, key: ServerKey, head: SharedHead): TurnSlot? {
        val noneUp = parts.upstreams.noneUp(head.key)
        if (noneUp != null) {
            voice.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, noneUp)
            return null
        }
        return when (val verdict = parts.limits.begin(key.person)) {
            is LimitVerdict.Admitted -> verdict.slot
            is LimitVerdict.Refused -> {
                call.response.headers.append(HttpHeaders.RetryAfter, verdict.retryAfterSeconds.toString())
                voice.refuse(call, HttpStatus.TOO_MANY_REQUESTS, verdict.why)
                null
            }
        }
    }
}

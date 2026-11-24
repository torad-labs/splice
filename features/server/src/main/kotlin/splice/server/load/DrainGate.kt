// NEW: server mode (#791, `[server] drain_seconds`). How a request a key made is answered while the server drains at
// stop: a 503 with `Retry-After` in its client's shape, whatever it asked, so a client or a load balancer reads one
// answer on every route. The listener asks it of every request it admitted a key for; the forward_auth route, which
// checks its key itself, asks it of every request whose key it admitted.
package splice.server.load

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.share.RefusalVoice

internal class DrainGate(private val load: ServerLoad) {
    /** Whether [call] is refused because the server drains: when it is, it has been answered, in [voice]. */
    suspend fun refused(call: ApplicationCall, voice: RefusalVoice): Boolean {
        val refused = load.drainRefusal() ?: return false
        call.response.headers.append(HttpHeaders.RetryAfter, refused.retryAfterSeconds.toString())
        voice.refuse(call, refused.status, refused.why)
        return true
    }
}

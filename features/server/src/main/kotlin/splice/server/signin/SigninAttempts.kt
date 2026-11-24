package splice.server.signin

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import splice.core.wire.HttpStatus
import splice.server.MinuteStarts
import splice.server.caller.ClientAddresses
import splice.share.RefusalVoice
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** Browser and CLI code exchanges share the same per-client rate budget. */
internal class SigninAttempts(private val addresses: ClientAddresses, private val clock: Clock) {
    private val tries = ConcurrentHashMap<String, MinuteStarts>()

    fun address(call: ApplicationCall): String {
        val forwarded = call.request.headers.getAll(HttpHeaders.XForwardedFor)?.joinToString(",")
        return addresses.of(call.request.origin.remoteAddress, forwarded)
    }

    suspend fun admit(call: ApplicationCall, address: String, cap: Int, voice: RefusalVoice): Boolean {
        val wait = waitFor(address, cap) ?: return true
        call.response.headers.append(HttpHeaders.RetryAfter, wait.toString())
        val why = "too many sign-ins from this address (rate_per_minute = $cap); try again in $wait s"
        voice.refuse(call, HttpStatus.TOO_MANY_REQUESTS, why)
        return false
    }

    private fun waitFor(address: String, cap: Int): Long? {
        if (cap <= 0) return null
        val now = clock.millis()
        if (tries.size > MAX_TRACKED_ADDRESSES) {
            tries.entries.removeIf { (_, idle) -> synchronized(idle) { idle.count(now) == 0L } }
        }
        val starts = tries.computeIfAbsent(address) { MinuteStarts() }
        return synchronized(starts) {
            val wait = starts.waitFor(cap.toLong(), now)
            if (wait == null) starts.add(now)
            wait
        }
    }
}

// Forget idle entries once the map grows past an ordinary team's client population.
private const val MAX_TRACKED_ADDRESSES = 4_096

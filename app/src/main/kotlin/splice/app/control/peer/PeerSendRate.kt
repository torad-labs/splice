package splice.app.control.peer

import splice.core.util.WallClock
import splice.sessions.peer.store.PeerLimit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A peer's token bucket, in tokens and the time it was last topped up. */
private data class Bucket(val tokens: Double, val at: Long)

/** The answer to one send against the bucket: go, or wait this many whole seconds (the `Retry-After`). */
internal sealed class SendAllowance {
    data object Go : SendAllowance()

    data class Wait(val seconds: Long) : SendAllowance()
}

/** The send rate per peer (spec 3.4): a token bucket of the peer's burst, refilled at its sends a minute
 *  (PeerLimits: 20 and 60 by default, or the peer's own). A changed limit applies from the next send. */
internal class PeerSendRate(private val clock: WallClock = WallClock { System.currentTimeMillis() }) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun take(peer: String, limit: PeerLimit = PeerLimit()): SendAllowance {
        val burst = limit.burst.toDouble()
        val refillPerMs = limit.perMinute.toDouble() / TimeUnit.MINUTES.toMillis(1)
        var allowance: SendAllowance = SendAllowance.Go
        buckets.compute(peer) { _, held ->
            val now = clock()
            val current = held ?: Bucket(burst, now)
            val tokens = minOf(burst, current.tokens + (now - current.at) * refillPerMs)
            if (tokens >= 1.0) {
                allowance = SendAllowance.Go
                Bucket(tokens - 1.0, now)
            } else {
                val waitSeconds = (1.0 - tokens) / refillPerMs / TimeUnit.SECONDS.toMillis(1)
                allowance = SendAllowance.Wait(kotlin.math.ceil(waitSeconds).toLong().coerceAtLeast(1))
                Bucket(tokens, now)
            }
        }
        return allowance
    }

    /** Forgets [peer]'s bucket, on revoke. */
    fun forget(peer: String) {
        buckets.remove(peer)
    }
}

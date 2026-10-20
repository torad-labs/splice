package splice.app.control.mount

import splice.core.util.WallClock
import java.util.concurrent.ConcurrentHashMap

// why: spec 3.4. A peer is a script or a bot, and 20 a minute is far past any conversation while still small enough
// that a looping script cannot flood a session's 50-message queue.
private const val SENDS_PER_MINUTE = 20.0

// why: spec 3.4. Five lets a script answer a burst of messages at once without waiting on the refill.
private const val SEND_BURST = 5.0

private const val MS_PER_MINUTE = 60_000.0
private const val MS_PER_SECOND = 1_000.0

/** A peer's token bucket, in tokens and the time it was last topped up. */
private data class Bucket(val tokens: Double, val at: Long)

/** The answer to one send against the bucket: go, or wait this many whole seconds (the `Retry-After`). */
internal sealed class SendAllowance {
    data object Go : SendAllowance()

    data class Wait(val seconds: Long) : SendAllowance()
}

/** The send rate per peer (spec 3.4): a token bucket of [SEND_BURST], refilled at [SENDS_PER_MINUTE]. */
internal class PeerSendRate(private val clock: WallClock = WallClock { System.currentTimeMillis() }) {
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val refillPerMs = SENDS_PER_MINUTE / MS_PER_MINUTE

    fun take(peer: String): SendAllowance {
        var allowance: SendAllowance = SendAllowance.Go
        buckets.compute(peer) { _, held ->
            val now = clock()
            val current = held ?: Bucket(SEND_BURST, now)
            val tokens = minOf(SEND_BURST, current.tokens + (now - current.at) * refillPerMs)
            if (tokens >= 1.0) {
                allowance = SendAllowance.Go
                Bucket(tokens - 1.0, now)
            } else {
                val waitMs = (1.0 - tokens) / refillPerMs
                allowance = SendAllowance.Wait(kotlin.math.ceil(waitMs / MS_PER_SECOND).toLong().coerceAtLeast(1))
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

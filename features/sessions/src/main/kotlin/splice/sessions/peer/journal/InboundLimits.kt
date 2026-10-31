// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 3.4): what a helper lets in. One token bucket per claimed sender
// address and one for the helper overall. A frame past either is refused with a receipt and never journaled. The
// buckets are integers: a token is 1,000 units, and a bucket fills in proportion to the milliseconds since it was
// last asked.
package splice.sessions.peer.journal

import splice.core.util.ElapsedClock

// why: spec 3.4, one sender address may send 60 a minute.
private const val PEER_INBOUND_ADDRESS_PER_MINUTE = 60

// why: spec 3.4, one sender address may send a burst of 20 before the minute rate applies.
private const val PEER_INBOUND_ADDRESS_BURST = 20

// why: spec 3.4, the helper takes 300 a minute from all senders together.
private const val PEER_INBOUND_TOTAL_PER_MINUTE = 300

// why: the spec fixes no burst for the whole helper; 100 is five senders' bursts, then the minute rate applies.
private const val PEER_INBOUND_TOTAL_BURST = 100

// why: a same-user process can claim any address, so the table of buckets is bounded; past it the oldest bucket goes.
private const val PEER_INBOUND_MAX_ADDRESSES = 1024

// why: a token is counted in thousandths so a slow rate still fills in whole units per millisecond.
private const val PEER_INBOUND_TOKEN_UNITS = 1000L

// why: the rates are per minute and the clock counts milliseconds.
private const val PEER_INBOUND_MINUTE_MS = 60_000L

internal class InboundLimits(private val elapsed: ElapsedClock) {
    private val lock = Any()
    private val perAddress = LinkedHashMap<String, Bucket>()
    private val overall = Bucket(PEER_INBOUND_TOTAL_BURST, PEER_INBOUND_TOTAL_PER_MINUTE, elapsed())

    private class Bucket(burst: Int, private val perMinute: Int, startMs: Long) {
        private val capacity: Long = burst * PEER_INBOUND_TOKEN_UNITS
        private var units: Long = capacity
        private var stamped: Long = startMs

        fun take(nowMs: Long): Boolean {
            val since = (nowMs - stamped).coerceAtLeast(0L)
            units = minOf(capacity, units + since * perMinute * PEER_INBOUND_TOKEN_UNITS / PEER_INBOUND_MINUTE_MS)
            stamped = nowMs
            val allowed = units >= PEER_INBOUND_TOKEN_UNITS
            if (allowed) units -= PEER_INBOUND_TOKEN_UNITS
            return allowed
        }
    }

    /** True when a frame claiming [address] (null when it claims none) may be taken now, and spends a token for it. */
    fun admit(address: String?): Boolean = synchronized(lock) {
        val now = elapsed()
        bucketFor(address.orEmpty(), now).take(now) && overall.take(now)
    }

    private fun bucketFor(key: String, now: Long): Bucket {
        val held = perAddress[key]
        if (held != null) return held
        if (perAddress.size >= PEER_INBOUND_MAX_ADDRESSES) perAddress.remove(perAddress.keys.first())
        val fresh = Bucket(PEER_INBOUND_ADDRESS_BURST, PEER_INBOUND_ADDRESS_PER_MINUTE, now)
        perAddress[key] = fresh
        return fresh
    }
}

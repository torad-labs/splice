// NEW: LAN share, round 16 of PR #419's review. The goal: however often a joined machine says it looks, the mirrors of
// its sessions here last at least a second and at most a minute, and a machine that says it looks less often never has
// them last less long than one that looks more often. `look_ms` comes from that machine's offer as it sent it, so the
// property runs over generated values, the largest and the negative among them. A rule worth pinning alone.
package splice.share

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// why: a fixed seed, so a failing draw is the same draw next time.
private const val LEASE_SEED = 16

// why: draws over every Long and over the looks a machine really asks for, enough to land on each side of the minute.
private const val LEASE_DRAWS = 200

class ShareLeaseTest {
    @Test
    fun `whatever look a machine asks for, its mirrors last between a second and a minute, longer for a longer look`() {
        val random = Random(LEASE_SEED)
        val edges = listOf(
            Long.MIN_VALUE,
            -1L,
            0L,
            1L,
            Long.MAX_VALUE / LEASE_LOOKS,
            Long.MAX_VALUE / LEASE_LOOKS + 1,
            Long.MAX_VALUE,
        )
        val drawn = List(LEASE_DRAWS) { random.nextLong() } + List(LEASE_DRAWS) { random.nextLong(0, LEASE_MAX_MS) }
        val leases = (edges + drawn).sorted().map { it to ShareLease.of(it) }

        leases.forEach { (look, lease) ->
            assertTrue(lease in LEASE_MIN_MS..LEASE_MAX_MS, "a look of $look ms lasts $lease ms")
        }
        leases.zipWithNext().forEach { (shorter, longer) ->
            val said = "a look of ${longer.first} ms lasts no less than one of ${shorter.first}"
            assertTrue(longer.second >= shorter.second, said)
        }
    }
}

// WALLS for the hourly quota rollup. This store is the only place splice counts the quantity the
// plan actually meters, so its failure mode is silent: an under-count reads as headroom that does
// not exist. Each test below pins one property the burn page depends on being true.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.TurnEconomics
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR = 3_600_000L

private fun turn(
    inTokens: Long = 0,
    cached: Long = 0,
    out: Long = 0,
    req: Long? = null,
    upstream: Long? = null,
    eager: Long? = null,
    deferred: Long? = null,
    rateLimited: Boolean = false,
) = TurnEconomics(inTokens, cached, out, req, upstream, eager, deferred, rateLimited)

class EconomicsStoreTest {

    @Test
    fun `turns in the same hour fold into one bucket`(@TempDir tmp: Path) {
        var now = 10 * HOUR
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { now })
        store.record(turn(inTokens = 100, cached = 90, out = 5))
        now += 60_000 // a minute later, same hour
        store.record(turn(inTokens = 200, cached = 150, out = 7))

        val buckets = store.read()
        assertEquals(1, buckets.size, "one hour must produce exactly one bucket")
        assertEquals(2, buckets[0].turns)
        assertEquals(300, buckets[0].inTokens)
        assertEquals(240, buckets[0].cachedTokens)
        assertEquals(12, buckets[0].outTokens)
    }

    @Test
    fun `a new hour opens a new bucket`(@TempDir tmp: Path) {
        var now = 10 * HOUR
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { now })
        store.record(turn(inTokens = 100))
        now += HOUR
        store.record(turn(inTokens = 200))

        val buckets = store.read()
        assertEquals(2, buckets.size)
        assertTrue(buckets[0].hour < buckets[1].hour, "buckets read oldest first")
        assertEquals(100, buckets[0].inTokens)
        assertEquals(200, buckets[1].inTokens)
    }

    /** THE LOAD-BEARING ONE. cached is recorded ALONGSIDE input, never subtracted from it: the
     *  plan meters total input, so a store that netted these would under-count by the hit rate —
     *  90% on a warm head, which is precisely how a drain looks safe. */
    @Test
    fun `cached tokens are recorded beside input, never netted out of it`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { 10 * HOUR })
        store.record(turn(inTokens = 1_000_000, cached = 900_000))

        val b = store.read().single()
        assertEquals(1_000_000, b.inTokens, "in_tokens is the METERED total, cache hits included")
        assertEquals(900_000, b.cachedTokens)
    }

    /** A head whose dialect cannot defer reports null, and must not be counted in the average's
     *  denominator — otherwise "cannot defer" is diluted into a misleading deferral rate. */
    @Test
    fun `only turns that reported a tool partition count toward deferralTurns`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { 10 * HOUR })
        store.record(turn(eager = 28, deferred = 48)) // a responses-dialect turn
        store.record(turn(eager = null, deferred = null)) // a chat-dialect turn: no deferral at all

        val b = store.read().single()
        assertEquals(2, b.turns)
        assertEquals(1, b.deferralTurns, "the non-deferring turn must not enter the denominator")
        assertEquals(28, b.toolsEager)
        assertEquals(48, b.toolsDeferred)
    }

    @Test
    fun `a head that never defers keeps deferralTurns at zero so the UI can render it as absent`(
        @TempDir tmp: Path,
    ) {
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { 10 * HOUR })
        repeat(3) { store.record(turn(inTokens = 10)) }
        assertEquals(0, store.read().single().deferralTurns)
    }

    @Test
    fun `rate-limited turns are counted`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { 10 * HOUR })
        store.record(turn(rateLimited = true))
        store.record(turn(rateLimited = false))
        store.record(turn(rateLimited = true))
        assertEquals(2, store.read().single().rateLimited)
    }

    @Test
    fun `wire bytes accumulate on both sides of the seam`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { 10 * HOUR })
        store.record(turn(req = 100, upstream = 140))
        store.record(turn(req = 200, upstream = 180))
        val b = store.read().single()
        assertEquals(300, b.reqBytes)
        assertEquals(320, b.upstreamBytes)
    }

    /** Retention is what keeps the file small enough to read on every dashboard poll. */
    @Test
    fun `buckets older than the retention window are dropped`(@TempDir tmp: Path) {
        var now = 1_000 * HOUR
        val store = EconomicsStore(tmp.resolve("e.json"), WallClock { now })
        store.record(turn(inTokens = 1))
        now += 9 * 24 * HOUR // nine days later, past the 8-day window
        store.record(turn(inTokens = 2))

        val buckets = store.read()
        assertEquals(1, buckets.size, "the nine-day-old bucket must be gone")
        assertEquals(2, buckets[0].inTokens)
    }

    @Test
    fun `state survives a restart`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        val first = EconomicsStore(file, WallClock { 10 * HOUR })
        first.record(turn(inTokens = 500, cached = 400, eager = 14, deferred = 52, rateLimited = true))
        first.flushNow()
        AsyncFileIo.drain()
        assertTrue(Files.exists(file), "flushNow must land the snapshot on disk")

        val reopened = EconomicsStore(file, WallClock { 10 * HOUR })
        val b = reopened.read().single()
        assertEquals(500, b.inTokens)
        assertEquals(400, b.cachedTokens)
        assertEquals(14, b.toolsEager)
        assertEquals(52, b.toolsDeferred)
        assertEquals(1, b.deferralTurns)
        assertEquals(1, b.rateLimited)
    }

    @Test
    fun `a missing file reads as empty rather than throwing`(@TempDir tmp: Path) {
        assertEquals(emptyList<Any>(), EconomicsStore(tmp.resolve("absent.json"), WallClock { 0 }).read())
    }

    /** Telemetry must never take a turn down with it. */
    @Test
    fun `a corrupt file reads as empty rather than throwing`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        Files.writeString(file, "{ this is not the array we wrote")
        val store = EconomicsStore(file, WallClock { 10 * HOUR })
        assertEquals(emptyList<Any>(), store.read())

        store.record(turn(inTokens = 7)) // and recording still works afterwards
        assertNotNull(store.read().single())
        assertEquals(7, store.read().single().inTokens)
    }
}

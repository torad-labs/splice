// WALLS for the hourly quota rollup. This store is the only place splice counts the quantity the
// plan actually meters, so its failure mode is silent: an under-count reads as headroom that does
// not exist. Each test below pins one property the burn page depends on being true.
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.model.TurnPrice
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR = 3_600_000L
private const val FABLE = "claude-fable-5"
private const val HAIKU = "claude-haiku-4-5"

/** A store that prices nothing: every turn is unpriced (the tests below that are not about dollars). */
private val UNPRICED = TurnPrice(null)

private val FABLE_RATES = ModelRates(input = 15.0, cacheRead = 1.5, output = 75.0, cacheWrite = 18.75)
private val HAIKU_RATES = ModelRates(input = 1.0, cacheRead = 0.1, output = 5.0, cacheWrite = 1.25)

/** A head pinned to fable whose catalog carries both cards, as a Claude head's does. */
private val FABLE_HEAD = TurnPrice(
    ModelCatalog(
        discoveryPrefix = "claude-splice--",
        models = listOf(
            ModelEntry(FABLE, contextWindow = 200_000, rates = FABLE_RATES),
            ModelEntry(HAIKU, contextWindow = 200_000, rates = HAIKU_RATES),
        ),
        defaultContextWindow = 200_000,
        pinnedModel = FABLE,
    ),
)

private fun turn(
    inTokens: Long = 0,
    cached: Long = 0,
    cacheWrite: Long = 0,
    out: Long = 0,
    req: Long? = null,
    upstream: Long? = null,
    eager: Long? = null,
    deferred: Long? = null,
    rateLimited: Boolean = false,
    model: String? = null,
) = TurnEconomics(model, inTokens, cached, cacheWrite, out, req, upstream, eager, deferred, rateLimited)

class EconomicsStoreTest {

    // V4-221: an hour mixes models, and the console priced the hour's sums at the pinned card. A
    // haiku subagent turn on a fable head cost fifteen times what it did.
    @Test
    fun `a haiku turn on a fable-pinned head is priced at haiku's card`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 100_000, cached = 60_000, cacheWrite = 10_000, out = 2_000, model = HAIKU))

        val b = store.read().single()
        val buckets = TokenBuckets(input = 30_000, cacheRead = 60_000, cacheWrite = 10_000, output = 2_000)
        assertEquals(TokenCost().of(buckets, HAIKU_RATES), b.costUsd!!, 1e-9, "haiku's card, not fable's")
        assertEquals(0, b.unpricedTurns)
    }

    @Test
    fun `turns on two models in one hour sum at their own cards`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 1_000, out = 100, model = FABLE))
        store.record(turn(inTokens = 1_000, out = 100, model = HAIKU))

        val each = TokenBuckets(input = 1_000, output = 100)
        val expected = TokenCost().of(each, FABLE_RATES) + TokenCost().of(each, HAIKU_RATES)
        assertEquals(expected, store.read().single().costUsd!!, 1e-9)
    }

    @Test
    fun `a turn with no card counts as unpriced, never as zero dollars`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 1_000, out = 100, model = "gpt-5.6-sol"))
        store.record(turn(inTokens = 1_000, out = 100, model = HAIKU))

        val b = store.read().single()
        assertEquals(1, b.unpricedTurns, "the card-less turn is named, not folded in at \$0")
        assertEquals(TokenCost().of(TokenBuckets(input = 1_000, output = 100), HAIKU_RATES), b.costUsd!!, 1e-9)
    }

    @Test
    fun `the priced hour persists and reloads`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        val first = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR })
        first.record(turn(inTokens = 1_000, out = 100, model = HAIKU))
        first.record(turn(inTokens = 1_000, out = 100, model = null))
        first.flushNow()
        AsyncFileIo.drain()

        val b = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR }).read().single()
        assertEquals(TokenCost().of(TokenBuckets(input = 1_000, output = 100), HAIKU_RATES), b.costUsd!!, 1e-9)
        assertEquals(1, b.unpricedTurns)
    }

    // An hour written before the field was not priced then: null, never $0, and it stays null when
    // this daemon adds turns to that same hour, because a sum missing the earlier turns would read as
    // the whole hour's cost.
    @Test
    fun `an hour written before the cost field reads null and stays null`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        Files.writeString(
            file,
            """[{"hour":36000000,"turns":3,"in_tokens":1000,"cached_tokens":900,"cache_write_tokens":0,""" +
                """"out_tokens":40,"req_bytes":700,"upstream_req_bytes":640,"tools_eager":28,""" +
                """"tools_deferred":48,"deferral_turns":2,"rate_limited":1}]""" + "\n",
        )
        val store = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR })
        assertEquals(null, store.read().single().costUsd, "not priced then")

        store.record(turn(inTokens = 1_000, out = 100, model = HAIKU))
        val b = store.read().single()
        assertEquals(null, b.costUsd, "a partial sum must not read as the hour's cost")
        assertEquals(4, b.turns)
    }

    @Test
    fun `turns in the same hour fold into one bucket`(@TempDir tmp: Path) {
        var now = 10 * HOUR
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { now })
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
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { now })
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
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 1_000_000, cached = 900_000))

        val b = store.read().single()
        assertEquals(1_000_000, b.inTokens, "in_tokens is the METERED total, cache hits included")
        assertEquals(900_000, b.cachedTokens)
    }

    /** A head whose dialect cannot defer reports null, and must not be counted in the average's
     *  denominator — otherwise "cannot defer" is diluted into a misleading deferral rate. */
    @Test
    fun `only turns that reported a tool partition count toward deferralTurns`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { 10 * HOUR })
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
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { 10 * HOUR })
        repeat(3) { store.record(turn(inTokens = 10)) }
        assertEquals(0, store.read().single().deferralTurns)
    }

    @Test
    fun `rate-limited turns are counted`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { 10 * HOUR })
        store.record(turn(rateLimited = true))
        store.record(turn(rateLimited = false))
        store.record(turn(rateLimited = true))
        assertEquals(2, store.read().single().rateLimited)
    }

    @Test
    fun `wire bytes accumulate on both sides of the seam`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { 10 * HOUR })
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
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { now })
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
        val first = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        first.record(turn(inTokens = 500, cached = 400, eager = 14, deferred = 52, rateLimited = true))
        first.flushNow()
        AsyncFileIo.drain()
        assertTrue(Files.exists(file), "flushNow must land the snapshot on disk")

        val reopened = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        val b = reopened.read().single()
        assertEquals(500, b.inTokens)
        assertEquals(400, b.cachedTokens)
        assertEquals(14, b.toolsEager)
        assertEquals(52, b.toolsDeferred)
        assertEquals(1, b.deferralTurns)
        assertEquals(1, b.rateLimited)
    }

    /** V4-86 THE ROUND TRIP. A cache write is a DISJOINT read-off of the metered input, beside the
     *  cache READ and never netted out of either, and it has to survive the file — the burn page
     *  reads the file, not the memory. Both cache buckets together here exceed nothing and sum to
     *  less than input, which is the ordinary production shape (fresh + read + written = input). */
    @Test
    fun `a bucket carrying cache writes round-trips through the file`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        val first = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        first.record(turn(inTokens = 60_000, cached = 40_000, cacheWrite = 12_000, out = 500))
        first.record(turn(inTokens = 30_000, cached = 0, cacheWrite = 30_000, out = 7))
        first.flushNow()
        AsyncFileIo.drain()

        // The key is on the wire under the name the payload and the webui both read.
        assertTrue(
            Files.readString(file).contains("\"cache_write_tokens\":42000"),
            "the persisted shape must carry the bucket: " + Files.readString(file),
        )

        val b = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR }).read().single()
        assertEquals(90_000, b.inTokens, "input stays the METERED total, both cache buckets included")
        assertEquals(40_000, b.cachedTokens, "the read bucket is untouched by the write bucket")
        assertEquals(42_000, b.cacheWriteTokens, "12k + 30k written, summed and reloaded")
        assertEquals(507, b.outTokens)
    }

    /** V4-86 THE MIGRATION, and the only reason it is a test and not a comment: every economics.json
     *  on an operator's disk today was written by the 11-key shape, and a loader that dropped such a
     *  row (or threw on it) would erase the burn page's entire week on upgrade. The absent key reads
     *  as 0, which is the TRUE historical value — nothing counted cache writes before this row — and
     *  every other field on the old row must still arrive. Hand-written rather than produced by an
     *  old build, so the shape is pinned as LITERAL BYTES and cannot drift with the writer. */
    @Test
    fun `an economics file written before the cache-write bucket loads with cacheWrite zero`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("e.json")
        Files.writeString(
            file,
            """[{"hour":36000000,"turns":3,"in_tokens":1000,"cached_tokens":900,"out_tokens":40,""" +
                """"req_bytes":700,"upstream_req_bytes":640,"tools_eager":28,"tools_deferred":48,""" +
                """"deferral_turns":2,"rate_limited":1}]""" + "\n",
        )

        val b = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR }).read().single()
        assertEquals(0, b.cacheWriteTokens, "an absent key is 0, never a dropped row and never a throw")
        assertEquals(36_000_000, b.hour, "the old row is still PLACED")
        assertEquals(3, b.turns)
        assertEquals(1_000, b.inTokens)
        assertEquals(900, b.cachedTokens)
        assertEquals(40, b.outTokens)
        assertEquals(700, b.reqBytes)
        assertEquals(640, b.upstreamBytes)
        assertEquals(28, b.toolsEager)
        assertEquals(48, b.toolsDeferred)
        assertEquals(2, b.deferralTurns)
        assertEquals(1, b.rateLimited)
    }

    /** The old row must stay MERGEABLE, not merely readable: a turn recorded into the same hour
     *  after the upgrade adds its cache write to a bucket that arrived carrying none. */
    @Test
    fun `a turn with cache writes folds into a bucket loaded from the old shape`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        Files.writeString(file, """[{"hour":36000000,"turns":1,"in_tokens":1000,"cached_tokens":900}]""")

        val store = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 5_000, cacheWrite = 5_000))

        val b = store.read().single()
        assertEquals(2, b.turns, "one hour, one bucket, the old turn still counted")
        assertEquals(6_000, b.inTokens)
        assertEquals(5_000, b.cacheWriteTokens, "the new bucket starts from the old row's implicit 0")
    }

    @Test
    fun `a missing file reads as empty rather than throwing`(@TempDir tmp: Path) {
        assertEquals(emptyList<Any>(), EconomicsStore(tmp.resolve("absent.json"), UNPRICED, WallClock { 0 }).read())
    }

    /** Telemetry must never take a turn down with it. */
    @Test
    fun `a corrupt file reads as empty rather than throwing`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        Files.writeString(file, "{ this is not the array we wrote")
        val store = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        assertEquals(emptyList<Any>(), store.read())

        store.record(turn(inTokens = 7)) // and recording still works afterwards
        assertNotNull(store.read().single())
        assertEquals(7, store.read().single().inTokens)
    }
}

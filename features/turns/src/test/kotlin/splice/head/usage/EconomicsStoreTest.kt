// WALLS for the hourly quota rollup. This store is the only place splice counts the quantity the
// plan actually meters, so its failure mode is silent: an under-count reads as headroom that does
// not exist. Each test below pins one property the burn page depends on being true.
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.model.TurnPrice
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.turn.AbsorbedRounds
import splice.core.turn.UsageHistory
import splice.core.turn.noRequestUsage
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

private const val HOUR = 3_600_000L
private const val FABLE = "claude-fable-5"
private const val HAIKU = "claude-haiku-4-5"

/** A store with no rate cards for posted requests (the tests below that are not about dollars). */
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
    inTokens: Long? = 0,
    cached: Long? = 0,
    cacheWrite: Long? = 0,
    out: Long? = 0,
    model: String? = null,
) = TurnEconomics(
    model = model,
    tokens = TurnTokens(inTokens = inTokens, cachedTokens = cached, cacheWriteTokens = cacheWrite, outTokens = out),
    bytes = TurnBytes(reqBytes = null, upstreamBytes = null),
    tools = TurnTools(toolsEager = null, toolsDeferred = null),
    history = UsageHistory(),
)

class UnknownFailureEconomicsTest {
    @Test
    fun `a no-request refusal is exact zero in the persisted hour`(@TempDir tmp: Path) {
        val file = tmp.resolve("refusal-economics.json")
        val store = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR })
        val refusal = turn(inTokens = null, cached = null, cacheWrite = null, out = null, model = HAIKU)
            .copy(history = noRequestUsage.origin.history)
        store.record(refusal)
        store.flushNow()
        val kept = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR }).read().single()
        assertEquals(1L, kept.turns)
        assertEquals(0L, kept.counts.unreportedUsageTurns)
        assertEquals(0L, kept.unpricedTurns)
        assertEquals(0.0, kept.costUsd)
        assertEquals(0.0, FABLE_HEAD.usd(HAIKU, refusal.counters()))
    }

    @Test
    fun `a no-request hour remains priced when the head has no rate card`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("no-card-hour.json"), UNPRICED, WallClock { 10 * HOUR })
        val refusal = turn(inTokens = null, cached = null, cacheWrite = null, out = null)
            .copy(history = noRequestUsage.origin.history)
        store.record(refusal)
        store.flushNow()
        val hour = store.read().single()
        assertEquals(0L, hour.counts.unreportedUsageTurns)
        assertEquals(0L, hour.unpricedTurns)
        assertEquals(0.0, hour.costUsd)
    }

    @Test
    fun `missing cache usage and an unknown earlier bill both mark the hourly totals`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("coverage.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 100, cached = null, out = 7, model = HAIKU))
        store.record(turn(inTokens = 100, out = 7, model = HAIKU).copy(history = UsageHistory(cutRounds = 1)))
        val bucket = store.read().single()
        assertEquals(200L, bucket.inTokens)
        assertEquals(14L, bucket.outTokens)
        assertEquals(2L, bucket.counts.unreportedUsageTurns)
        assertEquals(2L, bucket.unpricedTurns)
        store.flushNow()
    }

    @Test
    fun `unknown usage remains unpriced and carries a persisted unknown-turn count`(@TempDir tmp: Path) {
        val file = tmp.resolve("economics.json")
        val store = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR })
        val unknown = turn(inTokens = null, cached = null, cacheWrite = null, out = null, model = HAIKU)
        assertTrue(unknown.counters().isEmpty())
        assertNull(FABLE_HEAD.usd(HAIKU, unknown.counters()))
        store.record(unknown)
        store.record(turn(inTokens = 100, out = 7, model = HAIKU))
        store.flushNow()
        AsyncFileIo.drain()
        val kept = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR }).read().single()
        assertEquals(100L, kept.inTokens, "known counts survive an unknown turn")
        assertEquals(7L, kept.outTokens)
        assertEquals(1L, kept.counts.unreportedUsageTurns)
        assertEquals(1L, kept.unpricedTurns)
        assertEquals(
            TokenCost().of(TokenBuckets(input = 100, output = 7), HAIKU_RATES),
            requireNotNull(kept.costUsd),
            1e-9,
        )
    }

    @Test
    fun `input-only failure preserves input but never prices the absent output`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("economics.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 100, out = null, model = HAIKU))
        val kept = store.read().single()
        assertEquals(100L, kept.inTokens)
        assertEquals(1L, kept.counts.unreportedUsageTurns)
        assertEquals(1L, kept.unpricedTurns)
        store.flushNow()
        AsyncFileIo.drain()
    }

    @Test
    fun `reported zero usage is priced and does not count as unreported`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("economics.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(model = HAIKU))
        val kept = store.read().single()
        assertEquals(0L, kept.counts.unreportedUsageTurns)
        assertEquals(0L, kept.unpricedTurns)
        assertEquals(0.0, kept.costUsd)
        store.flushNow()
        AsyncFileIo.drain()
    }

    @Test
    fun `an old hourly file keeps its counters and defaults only the additive unknown count`(@TempDir tmp: Path) {
        val file = tmp.resolve("economics.json")
        Files.writeString(
            file,
            """[{"hour":36000000,"turns":1,"in_tokens":100,"cached_tokens":20,"out_tokens":7,"cost_usd":0.5}]""",
        )
        val kept = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR }).read().single()
        assertEquals(100L, kept.inTokens)
        assertEquals(20L, kept.cachedTokens)
        assertEquals(7L, kept.outTokens)
        assertEquals(0L, kept.cacheWriteTokens, "legacy decoding is unchanged")
        assertEquals(0.5, kept.costUsd)
        assertEquals(0L, kept.counts.unreportedUsageTurns)
    }
}

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

    // A turn whose code-mode script ran a hidden round billed two requests. The plan meters both, and
    // each prices at its own size; the row's in_tokens is only the final one.
    @Test
    fun `a turn's absorbed rounds are metered and priced as requests of their own`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), FABLE_HEAD, WallClock { 10 * HOUR })
        val script = AbsorbedRounds(rounds = 1, inputTokens = 40_000, cachedTokens = 30_000, outputTokens = 500)
        val absorbing = turn(inTokens = 60_000, cached = 50_000, out = 1_500, model = HAIKU)
        store.record(absorbing.copy(history = UsageHistory(absorbed = script)))

        val b = store.read().single()
        assertEquals(100_000, b.inTokens, "both requests' input is metered")
        assertEquals(80_000, b.cachedTokens)
        val cost = TokenCost()
        val expected = cost.of(TokenBuckets(input = 10_000, cacheRead = 50_000, output = 1_000), HAIKU_RATES) +
            cost.of(TokenBuckets(input = 10_000, cacheRead = 30_000, output = 500), HAIKU_RATES)
        assertEquals(expected, b.costUsd!!, 1e-9)
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
    fun `local code steps are separate from turns but their tokens and price are retained`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        val store = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 1_000, out = 10, model = FABLE))
        store.record(turn(inTokens = 70, out = 0, model = FABLE).copy(localStep = true))
        store.record(turn(inTokens = 20, model = null)) // legacy without a marker is a turn
        store.flushNow()
        AsyncFileIo.drain()

        val b = EconomicsStore(file, FABLE_HEAD, WallClock { 10 * HOUR }).read().single()
        assertEquals(2, b.turns)
        assertEquals(1, b.localSteps)
        assertEquals(1_090, b.inTokens)
        assertEquals(1, b.unpricedTurns)
        assertEquals(TokenCost().of(TokenBuckets(input = 1_070, output = 10), FABLE_RATES), b.costUsd!!, 1e-9)
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
        store.record(turn().copy(tools = TurnTools(toolsEager = 28, toolsDeferred = 48))) // a responses-dialect turn
        store.record(turn()) // a chat-dialect turn: no deferral at all

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
        store.record(turn().copy(rateLimited = true))
        store.record(turn())
        store.record(turn().copy(rateLimited = true))
        assertEquals(2, store.read().single().rateLimited)
    }

    @Test
    fun `wire bytes accumulate on both sides of the seam`(@TempDir tmp: Path) {
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { 10 * HOUR })
        store.record(turn().copy(bytes = TurnBytes(reqBytes = 100, upstreamBytes = 140)))
        store.record(turn().copy(bytes = TurnBytes(reqBytes = 200, upstreamBytes = 180)))
        val b = store.read().single()
        assertEquals(300, b.reqBytes)
        assertEquals(320, b.upstreamBytes)
    }

    /** Retention is what keeps the file small enough to read on every dashboard poll. A fresh install's
     *  window is a month and the days it is read over (Marlin, Oct 10, 2026), because plans and keys are
     *  paid by the month. */
    @Test
    fun `buckets older than the retention window are dropped`(@TempDir tmp: Path) {
        var now = 1_000 * HOUR
        val store = EconomicsStore(tmp.resolve("e.json"), UNPRICED, WallClock { now })
        store.record(turn(inTokens = 1))
        now += 30 * 24 * HOUR // a month later, still inside the window: the month's view needs it
        store.record(turn(inTokens = 2))
        assertEquals(2, store.read().size, "a month-old bucket is what the month's view is made of")

        now += 6 * 24 * HOUR // the first bucket is now 36 days old, past the window
        store.record(turn(inTokens = 4))
        val buckets = store.read()
        assertEquals(2, buckets.size, "the bucket past the window must be gone")
        assertEquals(listOf(2L, 4L), buckets.map { it.inTokens })
    }

    /** The window is the PERSON'S (Settings > Your data, Oct 10, 2026), not a number splice ships: a
     *  shorter one they set trims to their days, and the word forever trims nothing at all. */
    @Test
    fun `the hours are kept for the window the person set, and forever keeps every hour`(@TempDir tmp: Path) {
        var now = 1_000 * HOUR
        val week = EconomicsStore(
            tmp.resolve("week.json"),
            UNPRICED,
            WallClock { now },
            kept = KeptHistory { HistoryWindow(7) },
        )
        week.record(turn(inTokens = 1))
        now += 8 * 24 * HOUR
        week.record(turn(inTokens = 2))
        assertEquals(listOf(2L), week.read().map { it.inTokens }, "a seven-day window keeps seven days")

        var later = 1_000 * HOUR
        val kept = EconomicsStore(
            tmp.resolve("forever.json"),
            UNPRICED,
            WallClock { later },
            kept = KeptHistory { HistoryWindow(null) },
        )
        kept.record(turn(inTokens = 1))
        later += 400 * 24 * HOUR // more than a year on
        kept.record(turn(inTokens = 2))
        assertEquals(
            listOf(1L, 2L),
            kept.read().map { it.inTokens },
            "forever keeps the oldest hour: no cutoff is ever computed for it",
        )
    }

    /** A window of zero keeps TODAY, and today is the operator's own day. An hour bucket starts on a
     *  UTC hour, so in a half-hour zone the cutoff falls INSIDE the bucket a turn just after local
     *  midnight was recorded in: comparing starts would drop the spend the Day figure is made of. */
    @Test
    fun `keeping nothing still keeps the hour that today began inside`(@TempDir tmp: Path) {
        val kolkata = ZoneId.of("Asia/Kolkata") // UTC+5:30, so local midnight is 18:30 UTC
        val midnight = ZonedDateTime.of(2026, 10, 10, 0, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        var now = midnight + 20 * 60 * 1000 // 00:20 local, the first turn of the person's day
        val store = EconomicsStore(
            tmp.resolve("today.json"),
            UNPRICED,
            WallClock { now },
            kept = KeptHistory { HistoryWindow(0, kolkata) },
        )

        store.record(turn(inTokens = 11))
        now += 40 * 60 * 1000 // 01:00 local, still today, and the trim runs again
        store.record(turn(inTokens = 7))

        assertEquals(
            18L,
            store.read().sumOf { it.inTokens },
            "the spend of a day that began mid-hour is what that day's budget is measured against",
        )
    }

    /** Marcos asked twice for a setting to apply without a restart, and the console's rule is that a
     *  change reads "Applied" once (Marlin, Oct 10, 2026). The window is therefore read at every
     *  trim: a store that captured it would keep the old one until the daemon restarted. */
    @Test
    fun `shortening the window shortens it now, with no restart`(@TempDir tmp: Path) {
        var now = 1_000 * HOUR
        var window = HistoryWindow(90)
        val store = EconomicsStore(tmp.resolve("live.json"), UNPRICED, WallClock { now }, kept = KeptHistory { window })
        store.record(turn(inTokens = 1))
        now += 60 * 24 * HOUR
        store.record(turn(inTokens = 2))
        assertEquals(2, store.read().size, "both hours are inside the ninety days")

        window = HistoryWindow(7)
        now += HOUR
        store.record(turn(inTokens = 3))

        assertEquals(
            listOf(2L, 3L),
            store.read().map { it.inTokens },
            "the sixty-day-old hour went on the first trim after the window changed, not after a restart",
        )
    }

    /** The save on Settings > Your data: the person was shown a count at one moment and said yes to
     *  it, so the hours go at THAT moment. The same boundary as the window's own trim, because an
     *  hour starts on a UTC hour and a person's day can begin inside one. */
    @Test
    fun `the save trims the hours that end before the moment it was given`(@TempDir tmp: Path) {
        var now = 1_000 * HOUR
        val file = tmp.resolve("save.json")
        val store = EconomicsStore(file, UNPRICED, WallClock { now })
        store.record(turn(inTokens = 1))
        now += 2 * HOUR
        store.record(turn(inTokens = 2))

        assertEquals(
            0,
            store.trimBefore(1_000 * HOUR + HOUR / 2),
            "an hour the moment falls INSIDE is not over, so it stays whole",
        )
        assertEquals(1, store.trimBefore(1_001 * HOUR), "an hour that ended before the moment goes")

        assertEquals(listOf(2L), store.read().map { it.inTokens })
        assertFalse(
            Files.readString(file).contains("\"hour\":${1_000 * HOUR}"),
            "and it is gone from the file at once: a page read straight after the yes must not be served the old one",
        )
    }

    /** Found in review, Oct 10, 2026. A page poll that lands between the config patch and the save's
     *  explicit trim drops the expired hours from memory ITSELF, and the save then has nothing left
     *  to drop. A save that wrote only when its own call had moved something answered a clean
     *  "Applied" over a file that still held every expired hour, and the next boot read them back.
     *  The promise is about the file. */
    @Test
    fun `a read between the patch and the save still gets the expired hours off the disk`(@TempDir tmp: Path) {
        var now = 1_000 * HOUR
        var window = HistoryWindow(90)
        val file = tmp.resolve("raced.json")
        val store = EconomicsStore(file, UNPRICED, WallClock { now }, kept = KeptHistory { window })
        store.record(turn(inTokens = 1))
        now += 60 * 24 * HOUR
        store.record(turn(inTokens = 2))
        store.flushNow()
        assertTrue(Files.readString(file).contains("\"hour\":${1_000 * HOUR}"), "the old hour is on the disk")

        // The person saves "7 days": the window is patched, a poll lands and is served the new
        // window, and only then does the save reach this store.
        window = HistoryWindow(7)
        assertEquals(listOf(2L), store.read().map { it.inTokens }, "the poll is already served the shorter window")
        assertEquals(0, store.trimBefore(now - 7 * 24 * HOUR), "and the save finds nothing left to drop")

        assertFalse(
            Files.readString(file).contains("\"hour\":${1_000 * HOUR}"),
            "the expired spend is off the disk anyway: the save answers for the file, not for its own call",
        )
    }

    @Test
    fun `state survives a restart`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        val first = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        first.record(
            turn(inTokens = 500, cached = 400)
                .copy(tools = TurnTools(toolsEager = 14, toolsDeferred = 52), rateLimited = true),
        )
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

    /** V4-330. A record schedules the coalesced write a second out, and flushNow (a head's stop, a
     *  test's teardown) persists that version first, so the pending write finds nothing newer and
     *  must write nothing: the version guard in EconomicsStore.persist. Without it the late write
     *  lands after the stop, and at a teardown it re-created the deleted @TempDir (V4-329). The
     *  pending write runs here by its own method, not by waiting out its second. */
    @Test
    fun `the coalesced write after flushNow writes nothing`(@TempDir tmp: Path) {
        val file = tmp.resolve("e.json")
        val store = EconomicsStore(file, UNPRICED, WallClock { 10 * HOUR })
        store.record(turn(inTokens = 7))
        store.flushNow()
        Files.delete(file)

        store.flushScheduled()
        assertFalse(Files.exists(file), "the pending write found nothing newer than flushNow's and wrote nothing")
    }
}

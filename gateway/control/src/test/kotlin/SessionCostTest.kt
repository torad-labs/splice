// V4-37 — one session's spend, and the statusline segment that shows it.
//
// THE ROW SHAPE IS THE WHOLE POINT, and getting it wrong is what this row is being redone for.
// A perf row's `in_tokens` is INCLUSIVE of its `cached_tokens`: ChatUsage sets inputTokens from
// prompt_tokens (inclusive by the vendor's own definition) and PassthroughUsage.kt:23 spells it out
// — inputTokens = inputTokens + cacheRead + cacheCreation — after which TurnUsageStamp.kt:48 and :50
// write both counters straight from that object with NO subtraction. So the cache-miss bucket is the
// DIFFERENCE, and billing the raw field as a miss while also billing cached_tokens as a read charges
// the cached prefix twice.
//
// The first version of these tests never caught that, because every row they built was DISJOINT —
// a shape production never writes. This file therefore pins against REAL bytes: `realSessionRows`
// below are lines copied verbatim out of ~/.claude-codex/state/claude-deepseek-perf.jsonl (session
// tag 8b5c4f28, the session the operator's report came from) and parsed the way PerfStats.numericFields
// parses them. On the full 668-row session that file holds, the old arithmetic prices 46.465407 USD
// against a true 1.420652 — 32.7x — which is the defect. The three rows kept here reproduce it in
// miniature at 2.66x, and the rest of this file's synthetic rows were rewritten into the inclusive
// shape so none of them can encode the disjoint fiction again.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.HeadPerfSource
import splice.control.HeadSessionPerfSource
import splice.control.SessionCost
import splice.control.SessionCostSource
import splice.control.StatuslineRenderer
import splice.core.model.HeadRates
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates

class SessionCostTest {

    private val offPeak = ModelRates(input = 0.15, cacheRead = 0.003, output = 0.60)
    private val resellerMarkup = ModelRates(input = 0.30, cacheRead = 0.006, output = 1.20)

    private val sessionId = "sess-abcdefgh-1234"

    /** The measured 67-turn session (fresh input 305460, cache read 6911360, output 46897), split
     *  over three turns to prove the total is SUMMED — and written in the INCLUSIVE shape the sink
     *  actually appends, so each row's `in_tokens` already contains its `cached_tokens` and the
     *  fresh-input figure is what the subtraction has to recover. */
    private val measuredTurns = listOf(
        mapOf("in_tokens" to 2_100_000L, "cached_tokens" to 2_000_000L, "out_tokens" to 15_000L),
        mapOf("in_tokens" to 2_555_680L, "cached_tokens" to 2_455_680L, "out_tokens" to 16_000L),
        mapOf("in_tokens" to 2_561_140L, "cached_tokens" to 2_455_680L, "out_tokens" to 15_897L),
    )

    private fun deepseekCatalog() = ModelCatalog(
        discoveryPrefix = "claude-deepseek--",
        models = listOf(
            ModelEntry(
                id = "deepseek-flash",
                label = "DeepSeek V4.1 Flash",
                contextWindow = 1_000_000,
                rates = offPeak,
            ),
            ModelEntry(id = "deepseek-v4-pro", label = "DeepSeek V4 Pro", contextWindow = 1_000_000),
        ),
        defaultContextWindow = 1_000_000,
        pinnedModel = "deepseek-flash",
    )

    /** Answers [rows] for the session under test and NOTHING for any other id — the isolation the
     *  per-session reader must preserve. */
    private fun tokens(rows: List<Map<String, Long>>, forSession: String = sessionId) =
        HeadSessionPerfSource { asked -> if (asked == forSession) rows else emptyList() }

    // ---- the REAL rows ------------------------------------------------------------------------

    // Copied byte-for-byte out of the operator's own claude-deepseek-perf.jsonl. Nothing is edited,
    // reordered or trimmed: the field set, the key spellings and the values are the sink's. The first
    // row is a COLD turn (cached_tokens 0) on purpose — a subtraction that mishandles the no-cache
    // case would bill it wrong, and every session starts with one.
    private val realSessionJsonl = """
        {"ts":1789614116929,"model":"deepseek-flash","outcome":"ok","compact":false,"session":"8b5c4f28","gate":0,"recv":0,"parse":2,"build":2,"headers":935,"first_frame":936,"first_byte":943,"first_delta":2973,"stream_end":3313,"finish":3313,"total":3313,"inflight":4,"async_io_drops":0,"req_bytes":331923,"upstream_req_bytes":330586,"attempts":1,"write_ms":1,"frames_out":88,"bytes_out":11602,"sse_bytes_in":11533,"events_in":88,"content_frames_out":86,"in_tokens":82225,"out_tokens":113,"cached_tokens":0}
        {"ts":1789614119703,"model":"deepseek-flash","outcome":"ok","compact":false,"session":"8b5c4f28","gate":0,"recv":1,"parse":2,"build":2,"headers":1336,"first_frame":1336,"first_byte":1349,"first_delta":2337,"stream_end":2596,"finish":2596,"total":2596,"inflight":5,"async_io_drops":0,"req_bytes":448401,"upstream_req_bytes":446708,"attempts":1,"frames_out":75,"bytes_out":10109,"sse_bytes_in":10044,"events_in":75,"content_frames_out":73,"in_tokens":107790,"out_tokens":103,"cached_tokens":82304}
        {"ts":1789614122263,"model":"deepseek-flash","outcome":"ok","compact":false,"session":"8b5c4f28","gate":0,"recv":0,"parse":2,"build":2,"headers":1261,"first_frame":1261,"first_byte":1261,"first_delta":1640,"stream_end":2334,"finish":2334,"total":2334,"inflight":5,"async_io_drops":0,"req_bytes":453072,"upstream_req_bytes":451373,"attempts":1,"frames_out":170,"bytes_out":22409,"sse_bytes_in":22332,"events_in":170,"content_frames_out":168,"in_tokens":108852,"out_tokens":223,"cached_tokens":107776}
    """.trimIndent()

    private val realSessionRows: List<JsonObject> = realSessionJsonl.lines().map(::parseRow)

    private fun parseRow(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    /** The full client session id. Only the first 8 characters ever reach the file (TurnDrive
     *  truncates), so the tail is unknowable from the log by construction — which is exactly the
     *  prefix match the reader performs, reproduced here rather than assumed. */
    private val realSessionId = "8b5c4f28-9ad1-4c7a-b0e6-2f1c8d3e5a90"

    /** PerfStats.numericFields, reproduced: every top-level primitive that parses as a Long. */
    private fun numericFields(row: JsonObject): Map<String, Long> = buildMap {
        row.forEach { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { put(k, it) } }
    }

    /** PerfStats.belongsTo + numericFields over the real rows: the stored tag is a TRUNCATION of the
     *  id the caller holds, so the filter is `askedId.startsWith(storedTag)`. */
    private fun realTokens() = HeadSessionPerfSource { asked ->
        realSessionRows
            .filter { row -> (row["session"] as? JsonPrimitive)?.content?.let { asked.startsWith(it) } == true }
            .map { numericFields(it) }
    }

    @Test
    fun `real perf rows carry the cached prefix INSIDE in_tokens, which is what the arithmetic rests on`() {
        val rows = realTokens().tailNumericFor(realSessionId)
        assertEquals(3, rows.size, "the prefix match must find all three of this session's rows")
        for (row in rows) {
            val rawIn = row["in_tokens"]!!
            val cached = row["cached_tokens"]!!
            assertTrue(
                cached <= rawIn,
                "a production row never reports more cached than input; that is what makes it INCLUSIVE: $row",
            )
        }
        assertTrue(rows.any { it["cached_tokens"] == 0L }, "the cold first turn is kept, not filtered out")
        assertTrue(rows.any { (it["cached_tokens"] ?: 0L) > 0L }, "and at least one warm turn, or nothing is proven")
    }

    @Test
    fun `the operator's own rows price the cached prefix ONCE, not once as a miss and again as a read`() {
        val cost = SessionCost(realTokens(), deepseekCatalog())
        // Summed straight off the three rows above:
        //   in_tokens      82225 + 107790 + 108852 = 298867
        //   cached_tokens      0 +  82304 + 107776 = 190080
        //   out_tokens       113 +    103 +    223 =    439
        // cache MISS = 298867 - 190080 = 108787, because in_tokens already contains the cached part.
        //   108787 * 0.15   = 16318.05
        //   190080 * 0.003  =   570.24
        //      439 * 0.60   =   263.40
        //                      --------
        //                      17151.69 / 1e6 = 0.01715169
        assertEquals(0.01715169, cost.usdFor(realSessionId, "deepseek-flash")!!, 1e-12)
        // What the pre-redo arithmetic produced on these same bytes: it billed the raw 298867 as a
        // miss AND the 190080 again as a read.
        //   298867 * 0.15 = 44830.05, + 570.24 + 263.40 = 45663.69 / 1e6 = 0.04566369
        // 2.66x here; 32.7x across the full 668-row session (46.465407 against 1.420652), because
        // the longer the session the larger the cached share of every prompt.
        assertNotEquals(
            0.04566369,
            cost.usdFor(realSessionId, "deepseek-flash")!!,
            "the cached prefix must not be billed at the cache-MISS rate as well as the read rate",
        )
    }

    @Test
    fun `a row whose cached count exceeds its input floors the miss bucket instead of crediting it`() {
        // Not a shape the sink writes today, but an older or torn row could carry it, and a negative
        // miss bucket would SUBTRACT from the operator's bill rather than floor at zero.
        val impossible = listOf(mapOf("in_tokens" to 1_000L, "cached_tokens" to 9_000L, "out_tokens" to 0L))
        val cost = SessionCost(tokens(impossible), deepseekCatalog())
        // 0 miss + 9000 * 0.003 = 27.0 / 1e6
        assertEquals(0.000027, cost.usdFor(sessionId, "deepseek-flash")!!, 1e-12)
    }

    @Test
    fun `the measured session prices off its summed turns, not off one row`() {
        val cost = SessionCost(tokens(measuredTurns), deepseekCatalog())
        // in_tokens  2100000 + 2555680 + 2561140 = 7216820  (inclusive of cache)
        // cached     2000000 + 2455680 + 2455680 = 6911360
        // miss       7216820 - 6911360           =  305460  <- the report's "fresh input"
        //  305460 * 0.15   =  45819.0
        // 6911360 * 0.003  =  20734.08
        //   46897 * 0.60   =  28138.2      -> 94691.28 / 1e6 = 0.09469128
        assertEquals(0.09469128, cost.usdFor(sessionId, "deepseek-flash")!!, 1e-9)
        // Reading a single row instead of the sum would give a third of this; pin that too.
        val oneTurn = SessionCost(tokens(listOf(measuredTurns[0])), deepseekCatalog())
        assertNotEquals(
            cost.usdFor(sessionId, "deepseek-flash"),
            oneTurn.usdFor(sessionId, "deepseek-flash"),
            "the total must be the SUM over the session's turns",
        )
    }

    @Test
    fun `two heads on one provider price differently and never leak into each other`() {
        val catalog = deepseekCatalog()
        val heads = HeadRates { id -> if (id == "deepseek-flash") resellerMarkup else null }
        val markedUp = SessionCost(tokens(measuredTurns), catalog, headRates = heads)
        val atProvider = SessionCost(tokens(measuredTurns), catalog)

        val a = markedUp.usdFor(sessionId, "deepseek-flash")!!
        val b = atProvider.usdFor(sessionId, "deepseek-flash")!!
        assertEquals(0.18938256, a, 1e-9, "the head's own card wins")
        assertEquals(0.09469128, b, 1e-9, "the un-overridden head keeps the provider entry's card")
        assertEquals(2.0, a / b, 1e-9, "exactly the markup, so neither head's card reached the other")
    }

    @Test
    fun `an unknown session, an unrated model, and an empty session all fall back`() {
        val catalog = deepseekCatalog()
        val cost = SessionCost(tokens(measuredTurns), catalog)
        assertNull(
            cost.usdFor("sess-someone-else", "deepseek-flash"),
            "another session's rows are never this session's",
        )
        assertNull(cost.usdFor(null, "deepseek-flash"), "no session, no per-session number")
        assertNull(cost.usdFor(sessionId, null), "no model, no card")
        assertNull(cost.usdFor(sessionId, "deepseek-v4-pro"), "declared with no rates = client fallback")
        assertNull(SessionCost(tokens(measuredTurns), null).usdFor(sessionId, "deepseek-flash"), "no catalog, no card")
        val noTurns = SessionCost(tokens(emptyList()), catalog)
        assertNull(noTurns.usdFor(sessionId, "deepseek-flash"), "a session with no turns renders no cost")
    }

    @Test
    fun `a suffixed picker id resolves the card its bare upstream id declares`() {
        val cost = SessionCost(tokens(measuredTurns), deepseekCatalog())
        assertEquals(
            cost.usdFor(sessionId, "deepseek-flash"),
            cost.usdFor(sessionId, "deepseek-flash[1m]"),
            "the canonical id is the key, so a tier suffix does not lose the card",
        )
    }

    // ---- the segment itself ----------------------------------------------------------------

    private val blob = """
        {
          "model": { "id": "deepseek-flash", "display_name": "DeepSeek V4.1 Flash" },
          "cost": { "total_cost_usd": 3.69 },
          "context_window": { "context_window_size": 1000000, "used_percentage": 12 }
        }
    """.trimIndent()

    // The ESC byte (spelled `\u001b` rather than embedded raw, so it survives an editor) is part of
    // the pattern on purpose. A regex of `\[[0-9;]*m` alone leaves the ESC
    // behind, and the cost segment draws `$` and its digits either side of a DIM/RESET pair — so the
    // leftover ESC sits BETWEEN them and "$0.09" never matches. (StatuslineBarsTest gets away with
    // the shorter pattern because every token it asserts on is wrapped whole, never split.)
    private val ansi = Regex("\u001b\\[[0-9;]*m")

    /** The rendered line with the colour codes stripped. The `$` and its digits are drawn either
     *  side of a DIM/RESET pair, so the raw line never contains the literal "$0.09" — every
     *  assertion here is against the visible text the operator actually reads. */
    private fun segment(cost: SessionCostSource?, session: String = sessionId): String =
        StatuslineRenderer(label = "deepseek", sessionCost = cost)
            .render(blob, null, warnPct = 0, warnTokens5h = 0, sessionId = session)
            .replace(ansi, "")

    @Test
    fun `the segment shows splice's number, not the client's Anthropic-priced one`() {
        val line = segment(SessionCost(tokens(measuredTurns), deepseekCatalog()))
        assertTrue("$0.09" in line, line)
        assertTrue("3.69" !in line, "the client's Anthropic-priced total must not survive: $line")
    }

    @Test
    fun `the segment renders the REAL rows at the real rate, cent for cent`() {
        // 0.01715169 rounded to the two decimals the segment draws. The double-billed figure would
        // have rendered "$0.05" from the very same bytes.
        val line = segment(SessionCost(realTokens(), deepseekCatalog()), session = realSessionId)
        assertTrue("$0.02" in line, line)
        assertTrue("$0.05" !in line, "the double-billed figure must not survive: $line")
        assertTrue("3.69" !in line, "and neither must the client's Anthropic-priced total: $line")
    }

    @Test
    fun `a head with no rates renders the client number byte-identically to before`() {
        val withClient = segment(null)
        assertTrue("$3.69" in withClient, withClient)
        // No source at all is the pre-V4-37 render; an UNPRICEABLE session must land on that exact
        // same line too, never on a confident $0.00 and never on another session's number.
        assertEquals(
            withClient,
            segment(SessionCost(tokens(emptyList()), deepseekCatalog())),
            "an unpriced session falls back to the client's number, not to a zero",
        )
    }

    @Test
    fun `a plain HeadPerfSource is not session-aware, so the route builds no cost source for it`() {
        // The route bridges with a checked cast: `perf as? HeadSessionPerfSource`. A head whose perf
        // source is the plain reader — every test double, and any sink that keeps no session column —
        // misses that cast, so no SessionCost is built and the segment renders the client's number.
        val plain = HeadPerfSource { listOf(mapOf("in_tokens" to 1_000L)) }
        assertTrue(plain !is HeadSessionPerfSource, "the sibling interface is what the route looks for")
    }
}

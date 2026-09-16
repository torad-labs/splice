// V4-37 — one session's spend, and the statusline segment that shows it.
//
// The token totals here are the REAL 67-turn figures from the operator's report (fresh input
// 305460, cache read 6911360, output 46897), split across three turns to prove they are SUMMED
// rather than read off one row. At DeepSeek's off-peak flash card that is 0.09469128 USD — the
// 0.09 the operator's own arithmetic produced — where the client's Anthropic-priced number was 3.69.
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

    /** The measured session, split over three turns. */
    private val measuredTurns = listOf(
        mapOf("in_tokens" to 100_000L, "cached_tokens" to 2_000_000L, "out_tokens" to 15_000L),
        mapOf("in_tokens" to 100_000L, "cached_tokens" to 2_455_680L, "out_tokens" to 16_000L),
        mapOf("in_tokens" to 105_460L, "cached_tokens" to 2_455_680L, "out_tokens" to 15_897L),
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

    @Test
    fun `the measured session prices off its summed turns, not off one row`() {
        val cost = SessionCost(tokens(measuredTurns), deepseekCatalog())
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

    // The ESC byte is part of the pattern on purpose. A regex of `\[[0-9;]*m` alone leaves the ESC
    // behind, and the cost segment draws `$` and its digits either side of a DIM/RESET pair — so the
    // leftover ESC sits BETWEEN them and "$0.09" never matches. (StatuslineBarsTest gets away with
    // the shorter pattern because every token it asserts on is wrapped whole, never split.)
    private val ansi = Regex("\\[[0-9;]*m")

    /** The rendered line with the colour codes stripped. The `$` and its digits are drawn either
     *  side of a DIM/RESET pair, so the raw line never contains the literal "$0.09" — every
     *  assertion here is against the visible text the operator actually reads. */
    private fun segment(cost: SessionCostSource?): String =
        StatuslineRenderer(label = "deepseek", sessionCost = cost)
            .render(blob, null, warnPct = 0, warnTokens5h = 0, sessionId = sessionId)
            .replace(ansi, "")

    @Test
    fun `the segment shows splice's number, not the client's Anthropic-priced one`() {
        val line = segment(SessionCost(tokens(measuredTurns), deepseekCatalog()))
        assertTrue("$0.09" in line, line)
        assertTrue("3.69" !in line, "the client's Anthropic-priced total must not survive: $line")
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

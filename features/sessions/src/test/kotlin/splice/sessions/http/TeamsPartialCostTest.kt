// NEW: V4-264 — a four-member team's cost is a figure. In a recorded team run the lead ran 43 turns
// on claude-opus-5-5 and one on claude-haiku-4-5 (Claude Code's small-fast model), gpt ran 53 on
// gpt-6-sol, grok 38 on grok-4.7 and muse 21 on muse-spark-1.3. Its splice.toml had a card for each
// pinned model and none for haiku. The team tile read "API cost Estimated: - Unpriced": the lead's one
// haiku turn nulled the lead's whole figure, and the null reached the total. The turn counts, the
// models and which have a card are that run's; the sessions and the card values are invented.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.query.SessionHead
import splice.sessions.query.SessionPerfRow
import splice.sessions.query.SessionPerfSource
import splice.sessions.query.SessionPerfWindow
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import splice.sessions.transcript.SentTexts
import java.nio.file.Path

/** 2026-09-20T10:26:40Z, when the team was bound; its turns follow. */
private const val BOUND = 1_789_900_000_000L
private val OPUS = ModelRates(input = 5.0, cacheRead = 0.5, output = 25.0)
private val SOL = ModelRates(input = 1.25, cacheRead = 0.125, output = 10.0)
private val GROK = ModelRates(input = 3.0, cacheRead = 0.75, output = 15.0)
private val MUSE = ModelRates(input = 1.0, cacheRead = 0.1, output = 4.0)

/** Every turn here is 1,000 input and 100 output tokens: the dollars follow from the card. */
private val TURN = TokenBuckets(input = 1_000, output = 100)

/** The team's sessions by slot, invented. */
private val MEMBERS = mapOf(
    "claude" to "5f0c7a2e-1b4d-4c8e-9a61-0d3e2f4b6a01",
    "gpt" to "8a2d4e6f-3c5b-4f7a-b812-1e9d0c3b5a02",
    "grok" to "b3e5f7a9-4d6c-4e8b-8c23-2f0a1d4c6b03",
    "muse" to "c4f6a8b0-5e7d-4f9c-9d34-3a1b2e5d7c04",
)

class TeamsPartialCostTest {

    @TempDir
    lateinit var tmp: Path

    private fun rows(session: String, vararg runs: Pair<String, Int>): List<SessionPerfRow> {
        var at = BOUND
        return runs.flatMap { (model, count) ->
            List(count) {
                at += 1_000
                SessionPerfRow(at, "ok", mapOf("in_tokens" to 1_000L, "out_tokens" to 100L), model, session.take(8))
            }
        }
    }

    private fun head(rows: List<SessionPerfRow>, vararg models: Pair<String, ModelRates?>): SessionHead = SessionHead(
        perfRows = SessionPerfSource { since ->
            SessionPerfWindow(rows.filter { it.ts >= since }, rows.minOfOrNull { it.ts })
        },
        catalog = ModelCatalog(
            discoveryPrefix = "claude-team--",
            models = models.map { (id, rates) -> ModelEntry(id, id, contextWindow = 1_000_000, rates = rates) },
            defaultContextWindow = 1_000_000,
        ),
    )

    /** The team, bound to its four sessions: the id. */
    private fun team(store: TeamStore): String {
        val id = store.upsert(
            Team(
                name = "storefront",
                goal = "Keep the storefront API working and tested.",
                repo = "/tmp/storefront",
                slots = listOf(
                    TeamSlot(id = "claude", role = "lead", head = "claude-splice", lead = true),
                    TeamSlot(id = "gpt", role = "reviewer", head = "codex"),
                    TeamSlot(id = "grok", role = "builder", head = "grok"),
                    TeamSlot(id = "muse", role = "tester", head = "muse"),
                ),
            ),
        ).id
        store.bind(id, MEMBERS)
        return id
    }

    /** Each head's turns and the run's catalog for it: a card on the pinned model only. */
    private fun heads(): Map<String, SessionHead> = mapOf(
        "claude-splice" to head(
            rows(MEMBERS.getValue("claude"), "claude-opus-5-5" to 43, "claude-haiku-4-5" to 1),
            "claude-fable-5-1" to null,
            "claude-opus-5-5" to OPUS,
            "claude-sonnet-5" to null,
            "claude-haiku-4-5" to null,
        ),
        "codex" to head(rows(MEMBERS.getValue("gpt"), "gpt-6-sol" to 53), "gpt-6-sol" to SOL),
        "grok" to head(rows(MEMBERS.getValue("grok"), "grok-4.7" to 38), "grok-4.7" to GROK),
        "muse" to head(rows(MEMBERS.getValue("muse"), "muse-spark-1.3" to 21), "muse-spark-1.3[1m]" to MUSE),
    )

    @Test
    fun `the team prices every member, and the lead's one haiku turn is counted, not a dash`() {
        val store = TeamStore(tmp.resolve("state/teams.json"), WallClock { BOUND })
        val id = team(store)
        val heads = heads()
        val routes = TeamsRoutes(
            teams = TeamSource { store },
            heads = heads,
            registry = null,
            activity = ActivitySource { ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { BOUND }) },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { BOUND + 3_600_000 },
        )
        val slots = Json.parseToJsonElement(routes.economics(id).body).jsonObject.getValue("slots").jsonArray
            .map { it.jsonObject }.associateBy { it.getValue("slot").jsonPrimitive.content }
        val each = TokenCost()
        val expected = mapOf(
            "claude" to 43 * each.of(TURN, OPUS),
            "gpt" to 53 * each.of(TURN, SOL),
            "grok" to 38 * each.of(TURN, GROK),
            "muse" to 21 * each.of(TURN, MUSE),
        )
        expected.forEach { (slot, usd) ->
            assertEquals(usd, dollars(slots.getValue(slot)), 1e-9, "$slot's priced dollars")
        }
        assertEquals(
            mapOf("claude" to 1L, "gpt" to 0L, "grok" to 0L, "muse" to 0L),
            slots.mapValues { (_, tally) -> tally.getValue("unpriced_turns").jsonPrimitive.long },
            "the haiku turn is counted beside the lead's figure, not folded into it",
        )
    }

    private fun dollars(tally: JsonObject): Double {
        val cost = tally.getValue("cost_usd")
        return cost.jsonPrimitive.takeUnless { it.content == "null" }?.double ?: error("cost_usd is null: $tally")
    }
}

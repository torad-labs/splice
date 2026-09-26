// NEW: V4-263 — a member's subagent traffic is that member's tool traffic, never a team hand-off.
// A recorded four-member team run stored 16 edges: 13 between the members, and three from gpt's
// /code-review subagent, recorded as gpt's session to "code-review" and, for its replies, to "main".
// The board's Chat listed "gpt -> code-review" and Messages read 16. The edges below have that run's
// shape and order; their sessions, tool ids and times are invented.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path

/** 2026-09-20T10:40:00Z, inside the UTC day the edges below are on. */
private const val RUN_NOW = 1_789_900_800_000L

/** When the run's first edge was stored; the others follow it. */
private const val RUN_START = 1_789_900_000_000L
private const val CLAUDE = "5f0c7a2e-1b4d-4c8e-9a61-0d3e2f4b6a01"
private const val GPT = "8a2d4e6f-3c5b-4f7a-b812-1e9d0c3b5a02"
private const val GROK = "b3e5f7a9-4d6c-4e8b-8c23-2f0a1d4c6b03"
private const val MUSE = "c4f6a8b0-5e7d-4f9c-9d34-3a1b2e5d7c04"

/** The run's edge store in its order: (from, to, at, tool_use id). */
private val RUN_EDGES = listOf(
    MessageEdge(CLAUDE, "gpt", RUN_START, "toolu_run01"),
    MessageEdge(GPT, "claude", RUN_START + 20_000L, "call_run02"),
    MessageEdge(GPT, "claude", RUN_START + 55_000L, "call_run03"),
    MessageEdge(CLAUDE, "gpt", RUN_START + 70_000L, "toolu_run04"),
    MessageEdge(GPT, "code-review", RUN_START + 75_000L, "call_run05"),
    MessageEdge(GPT, "claude", RUN_START + 95_000L, "call_run06"),
    MessageEdge(GPT, "claude", RUN_START + 115_000L, "call_run07"),
    MessageEdge(GPT, "claude", RUN_START + 125_000L, "call_run08"),
    MessageEdge(GPT, "claude", RUN_START + 135_000L, "call_run09"),
    MessageEdge(CLAUDE, "gpt", RUN_START + 136_000L, "toolu_run10"),
    MessageEdge(GPT, "main", RUN_START + 150_000L, "call_run11"),
    MessageEdge(GPT, "main", RUN_START + 170_000L, "call_run12"),
    MessageEdge(GPT, "claude", RUN_START + 178_000L, "call_run13"),
    MessageEdge(GPT, "claude", RUN_START + 190_000L, "call_run14"),
    MessageEdge(CLAUDE, "gpt", RUN_START + 202_000L, "toolu_run15"),
    MessageEdge(GPT, "claude", RUN_START + 205_000L, "call_run16"),
)

class TeamsSubagentTrafficTest {

    @TempDir
    lateinit var tmp: Path

    /** The run's four members, each registered under its slot's name with its own messaging socket,
     *  as a team's sessions are while it runs. */
    private fun routes(): Pair<TeamsRoutes, String> {
        val clock = WallClock { RUN_NOW }
        val repo = Files.createDirectories(tmp.resolve("repo"))
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        val members = listOf("claude" to CLAUDE, "gpt" to GPT, "grok" to GROK, "muse" to MUSE)
        members.forEachIndexed { index, (name, session) ->
            val pid = index + 1
            Files.writeString(
                dir.resolve("$pid.json"),
                """{"pid":$pid,"sessionId":"$session","cwd":"$repo","updatedAt":$RUN_NOW,""" +
                    """"messagingSocketPath":"/run/$pid.sock","name":"$name"}""",
            )
        }
        val registry = SessionRegistry(dir, { SessionRoute.Head("codex") }, pidAlive = { true }, clock = clock)
        val store = TeamStore(tmp.resolve("state/teams.json"), clock)
        val slots = listOf("claude" to "lead", "gpt" to "reviewer", "grok" to "builder", "muse" to "tester")
        val id = store.upsert(
            Team(
                name = "storefront",
                goal = "ship",
                repo = repo.toString(),
                slots = slots.map { (slot, role) ->
                    TeamSlot(id = slot, role = role, head = "codex", lead = slot == "claude")
                },
            ),
        ).id
        store.bind(id, members.toMap())
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", clock)
        RUN_EDGES.forEach(stores.edges::record)
        AsyncFileIo.drain()
        val routes = TeamsRoutes(
            teams = TeamSource { store },
            heads = emptyMap(),
            registry = registry,
            activity = ActivitySource { stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = clock,
        )
        return routes to id
    }

    @Test
    fun `the run's chat is the 13 hand-offs between members, and gpt's code-review subagent is not one`() {
        val (routes, id) = routes()
        val messages = rows(routes.reads.chat(id, null).body, "messages")
        assertEquals(13, messages.size, "Messages reads 13")
        val pairs = messages.map { text(it, "from_slot") to text(it, "to_slot") }
        assertEquals(mapOf(("claude" to "gpt") to 4, ("gpt" to "claude") to 9), pairs.groupingBy { it }.eachCount())
        val targets = messages.map { text(it, "to") }.toSet()
        assertEquals(setOf("uds:/run/1.sock", "uds:/run/2.sock"), targets, "no code-review, no main")
    }

    @Test
    fun `the run's edges route carries no subagent traffic either`() {
        val (routes, id) = routes()
        val directions = rows(routes.reads.edges(id).body, "edges").map { text(it, "direction") }
        assertEquals(List(13) { "internal" }, directions)
    }

    private fun rows(body: String, array: String): List<JsonObject> =
        Json.parseToJsonElement(body).jsonObject.getValue(array).jsonArray.map { it.jsonObject }

    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content
}

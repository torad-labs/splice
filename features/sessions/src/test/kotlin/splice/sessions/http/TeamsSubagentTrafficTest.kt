// NEW: V4-263 — a member's subagent traffic is that member's tool traffic, never a team hand-off.
// Take 2 of the film (captures/film/team-storefront-2/edges-2026-09-26.take2.jsonl) held 16 edges:
// 13 between the four members, and three from gpt's /code-review subagent, recorded as gpt's session
// to "code-review" and, for its replies, to "main". The board's Chat listed "gpt -> code-review" and
// Messages read 16. The edges below are that file's, verbatim.
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

/** 2026-09-26T02:50:00Z, inside the UTC day take 2 recorded its edges on. */
private const val TAKE_2_NOW = 1_790_391_000_000L
private const val CLAUDE = "018ab973-b700-4eb0-afed-cb160b35f80c"
private const val GPT = "72cb7945-0c23-4dcb-ae42-816fa1d52ddb"
private const val GROK = "acce7fa1-88c0-46ce-b970-9f5248215945"
private const val MUSE = "ea73ec1b-fe3e-46d2-a8ff-6c0f0a82b326"

/** Take 2's edge store, line for line: (from, to, at, tool_use id). */
private val TAKE_2_EDGES = listOf(
    MessageEdge(CLAUDE, "gpt", 1_790_390_366_722L, "toolu_01NxuqvFhJuWdwqR8wcV6gRW"),
    MessageEdge(GPT, "claude", 1_790_390_387_147L, "call_6zCbtzRS2hdBrT0lS9uKP8Un"),
    MessageEdge(GPT, "claude", 1_790_390_423_224L, "call_lSQI8wQQfShYMLpE7P0y6sBI"),
    MessageEdge(CLAUDE, "gpt", 1_790_390_437_611L, "toolu_01Jn3gpPccRuY2cHgt6rEgUz"),
    MessageEdge(GPT, "code-review", 1_790_390_441_205L, "call_ayriZGddG7Wtrjfbj518bdTk"),
    MessageEdge(GPT, "claude", 1_790_390_463_418L, "call_iUgMrzhRKsmqDcQE9NIM3lWl"),
    MessageEdge(GPT, "claude", 1_790_390_483_807L, "call_lsfgkgQjNCEkbshtvWx7nxru"),
    MessageEdge(GPT, "claude", 1_790_390_492_895L, "call_jaGH5Byq8wRQ9xIAS3u4nR85"),
    MessageEdge(GPT, "claude", 1_790_390_501_171L, "call_iiNYNulz6LumbbcGBs0h1FSf"),
    MessageEdge(CLAUDE, "gpt", 1_790_390_502_263L, "toolu_01Y13KzLD7QiNHi1V3gJSaZx"),
    MessageEdge(GPT, "main", 1_790_390_515_965L, "call_7patUboL8FVggN4keCDJZfKQ"),
    MessageEdge(GPT, "main", 1_790_390_536_994L, "call_Ii2WAl65Zxc9AK3gcq2kXTus"),
    MessageEdge(GPT, "claude", 1_790_390_544_762L, "call_XbNuUk8houbbXOuz6hI0CEd4"),
    MessageEdge(GPT, "claude", 1_790_390_556_388L, "call_OrfKVUZc9Zcg4TKEa6uKDeV3"),
    MessageEdge(CLAUDE, "gpt", 1_790_390_568_513L, "toolu_011nxZANh8mQG5wBWRAJ7o5S"),
    MessageEdge(GPT, "claude", 1_790_390_571_774L, "call_DD6pNOpUPLmegcClDqt02DPj"),
)

class TeamsSubagentTrafficTest {

    @TempDir
    lateinit var tmp: Path

    /** Take 2's four members, each registered under its slot's name with its own messaging socket,
     *  as the film stack's sessions were while the take ran. */
    private fun routes(): Pair<TeamsRoutes, String> {
        val clock = WallClock { TAKE_2_NOW }
        val repo = Files.createDirectories(tmp.resolve("repo"))
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        val members = listOf("claude" to CLAUDE, "gpt" to GPT, "grok" to GROK, "muse" to MUSE)
        members.forEachIndexed { index, (name, session) ->
            val pid = index + 1
            Files.writeString(
                dir.resolve("$pid.json"),
                """{"pid":$pid,"sessionId":"$session","cwd":"$repo","updatedAt":$TAKE_2_NOW,""" +
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
        TAKE_2_EDGES.forEach(stores.edges::record)
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
    fun `take 2's chat is the 13 hand-offs between members, and gpt's code-review subagent is not one`() {
        val (routes, id) = routes()
        val messages = rows(routes.reads.chat(id, null).body, "messages")
        assertEquals(13, messages.size, "Messages reads 13")
        val pairs = messages.map { text(it, "from_slot") to text(it, "to_slot") }
        assertEquals(mapOf(("claude" to "gpt") to 4, ("gpt" to "claude") to 9), pairs.groupingBy { it }.eachCount())
        val targets = messages.map { text(it, "to") }.toSet()
        assertEquals(setOf("uds:/run/1.sock", "uds:/run/2.sock"), targets, "no code-review, no main")
    }

    @Test
    fun `take 2's edges route carries no subagent traffic either`() {
        val (routes, id) = routes()
        val directions = rows(routes.reads.edges(id).body, "edges").map { text(it, "direction") }
        assertEquals(List(13) { "internal" }, directions)
    }

    private fun rows(body: String, array: String): List<JsonObject> =
        Json.parseToJsonElement(body).jsonObject.getValue(array).jsonArray.map { it.jsonObject }

    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content
}

// NEW: V4-131 — the team routes' payloads, asserted on TeamsRoutes directly (TeamsRoutesWiringTest
// proves the ControlServer lines that route to them). Every read joins on EVERY session a slot ever
// held, so each test carries a rebound slot whose old session still counts; every unwired port answers
// its named 503 rather than an empty payload; a create is idempotent by its key.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.MessageEdge
import splice.sessions.query.SessionHead
import splice.sessions.transcript.SentTexts
import java.nio.file.Path

private const val DAY = 86_400_000L
private const val B1 = """[{"id":"b1","role":"builder","head":"codex"}]"""

class TeamsRoutesTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val asked = mutableListOf<String>()

    /** Found texts for toolu_a only, and a record of each lookup's sender, head and ids. */
    private val texts = SentTextSource { session, head, ids ->
        asked += "$session $head ${ids.sorted()}"
        val found = mapOf("toolu_a" to "build row 7").filterKeys { it in ids }
        SentTexts("/t/$session.jsonl", found, ids - "toolu_a")
    }

    private fun routes(wired: Boolean = true, heads: Map<String, SessionHead> = emptyMap()) = TeamsRoutes(
        teams = TeamSource { rig.store.takeIf { wired } },
        heads = heads,
        registry = rig.registry,
        activity = ActivitySource { rig.stores.takeIf { wired } },
        texts = texts,
        clock = WallClock { AT },
    )

    private fun idOf(body: String) = rig.json(body).getValue("id").jsonPrimitive.content

    @Test
    fun `a create is keyed, the same key answers the same team, and no key is refused`() {
        val routes = routes()
        val first = routes.create("""{"name":"atlas","slots":$B1}""", "k-1")
        assertEquals(HttpStatusCode.Created, first.status, first.body)
        val retried = routes.create("""{"name":"atlas","slots":$B1}""", "k-1")
        assertEquals(HttpStatusCode.OK, retried.status, "a retry is not a second create")
        assertEquals(idOf(first.body), idOf(retried.body))
        assertEquals(HttpStatusCode.Created, routes.create("""{"name":"atlas","slots":$B1}""", "k-2").status)
        assertEquals(2, rig.store.teams().size)
        val edited = routes.create("""{"name":"renamed","slots":$B1}""", "k-1")
        assertEquals(HttpStatusCode.Conflict, edited.status, "a reused key with an edited body is refused")
        val unkeyed = routes.create("""{"name":"atlas"}""", null)
        assertEquals(HttpStatusCode.BadRequest, unkeyed.status)
        val reason = "PUT /api/teams needs an Idempotency-Key header, so a retried create cannot make a second team"
        assertEquals("""{"error":"$reason"}""", unkeyed.body)
    }

    @Test
    fun `writes replace by path, bind, instruct and archive, refusing with the reason`() {
        val routes = routes()
        val id = idOf(routes.create("""{"name":"atlas","slots":$B1}""", "k").body)
        val carried = routes.create("""{"id":"$id","name":"x"}""", "k2")
        assertEquals(HttpStatusCode.BadRequest, carried.status)
        assertEquals("""{"error":"a new team takes no id; PUT /api/teams/$id replaces one"}""", carried.body)
        assertEquals(HttpStatusCode.BadRequest, routes.create("{ nope", "k3").status)
        assertEquals(HttpStatusCode.NotFound, routes.replace("team-gone", """{"name":"x"}""").status)
        val renamed = routes.replace(id, """{"id":"other","name":"renamed","slots":$B1}""")
        assertEquals(id, idOf(renamed.body), "the path names the team")
        assertEquals("k", rig.store.team(id)?.idempotencyKey, "a replace keeps the key the team was made under")
        assertEquals(HttpStatusCode.OK, routes.bind(id, """{"bindings":{"b1":"$BUILDER"}}""").status)
        val badSlot = routes.bind(id, """{"bindings":{"zz":"$BUILDER"}}""")
        assertEquals("""{"error":"no such slot in $id: zz"}""", badSlot.body)
        assertEquals(HttpStatusCode.NotFound, routes.bind("team-gone", """{"bindings":{}}""").status)
        val instructed = rig.json(routes.instruct(id, "b1", """{"instructions":"be terse"}""").body)
        assertEquals(listOf("be terse"), rig.column(instructed, "slots", "instructions"))
        assertEquals("true", rig.json(routes.archive(id).body).getValue("archived").jsonPrimitive.content)
        val listed = rig.json(routes.list().body).getValue("teams").jsonArray.single().jsonObject
        val b1 = listed.getValue("slots").jsonArray.single().jsonObject
        assertEquals(listOf(BUILDER), b1.getValue("sessions_history").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `unwired ports answer their named 503 and an unknown team is a 404`() {
        val routes = routes(wired = false)
        val unwired = """{"error":"the team store is not wired into this control plane"}"""
        val replies =
            listOf(routes.list(), routes.create("{}", "k"), routes.economics("x"), routes.reads.chat("x", null))
        for (reply in replies) {
            assertEquals(HttpStatusCode.ServiceUnavailable, reply.status)
            assertEquals(unwired, reply.body)
        }
        val id = rig.team().id
        val noStores = TeamsRoutes(TeamSource { rig.store }, emptyMap(), rig.registry, ActivitySource { null }, texts)
        assertEquals(
            """{"error":"the activity stores are not wired into this control plane"}""",
            noStores.reads.edges(id).body,
        )
        assertEquals(HttpStatusCode.NotFound, routes().reads.edges("team-gone").status)
        assertEquals(HttpStatusCode.NotFound, routes().economics("team-gone").status)
    }

    @Test
    fun `edges are the members' own, directed internal, out and in, a name resolved to its address`() {
        val id = rig.team().id
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", AT, "toolu_a"))
        rig.stores.edges.record(MessageEdge(OLD_BUILDER, "lead", AT + 1, "toolu_b"))
        rig.stores.edges.record(MessageEdge(BUILDER, "uds:/run/3.sock", AT + 2, "toolu_c"))
        rig.stores.edges.record(MessageEdge(OUTSIDER, "uds:/run/1.sock", AT + 3, "toolu_d"))
        rig.stores.edges.record(MessageEdge(OUTSIDER, "someone", AT + 4, "toolu_e"))
        AsyncFileIo.drain()
        val edge = { from: String, to: String, at: Long, dir: String, fromSlot: String?, toSlot: String? ->
            val slotOf = { s: String? -> s?.let { "\"$it\"" } ?: "null" }
            """{"from":"$from","to":"$to","at":$at,"direction":"$dir",""" +
                """"from_slot":${slotOf(fromSlot)},"to_slot":${slotOf(toSlot)}}"""
        }
        val expected = listOf(
            edge(LEAD, "uds:/run/2.sock", AT, "internal", "lead", "b1"),
            edge(OLD_BUILDER, "uds:/run/1.sock", AT + 1, "internal", "b1", "lead"),
            edge(BUILDER, "uds:/run/3.sock", AT + 2, "out", "b1", null),
            edge(OUTSIDER, "uds:/run/1.sock", AT + 3, "in", null, "lead"),
        )
        assertEquals(
            rig.json("""{"team_id":"$id","edges":[${expected.joinToString(",")}]}"""),
            rig.json(routes().reads.edges(id).body),
        )
    }

    @Test
    fun `chat is one UTC day of team edges with each text or the reason it has none`() {
        val id = rig.team().id
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", AT, "toolu_a"))
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", AT + 1, "toolu_b"))
        rig.stores.edges.record(MessageEdge(BUILDER, "lead", AT - DAY, "toolu_y"))
        AsyncFileIo.drain()
        val chat = rig.json(routes().reads.chat(id, null).body)
        assertEquals(DAY_START.toString(), chat.getValue("day_start_epoch_millis").jsonPrimitive.content)
        assertEquals(
            "no wire source: a SendMessage call carries no dispatch unit",
            chat.getValue("packet_note").jsonPrimitive.content,
        )
        val base = """"from":"$LEAD","from_slot":"lead","from_head":"claude",""" +
            """"to":"uds:/run/2.sock","to_slot":"b1","packet":null"""
        val source = "/t/$LEAD.jsonl"
        assertEquals(
            listOf(
                rig.json("""{"at":$AT,$base,"text":"build row 7","text_source":"$source","missing_reason":null}"""),
                rig.json(
                    """{"at":${AT + 1},$base,"text":null,"text_source":null,""" +
                        """"missing_reason":"the call is not in $source"}""",
                ),
            ),
            chat.getValue("messages").jsonArray.map { it.jsonObject },
        )
        assertEquals(listOf("$LEAD claude [toolu_a, toolu_b]"), asked, "one lookup per sender, only the day's ids")
        val yesterday = rig.json(routes().reads.chat(id, "2026-09-17").body)
        assertEquals(listOf(BUILDER), rig.column(yesterday, "messages", "from"), "only that day")
        val bad = routes().reads.chat(id, "yesterday")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("""{"error":"day is a UTC date, YYYY-MM-DD: yesterday"}""", bad.body)
    }

    @Test
    fun `activity is the members' labels on the day, the detail split off, and the upstream queries counted`() {
        val id = rig.team().id
        rig.stores.activity.label(OLD_BUILDER, "codex", "Running tests\nrow V4-131", AT)
        rig.stores.activity.label(LEAD, "claude", "Reviewing", AT + 1)
        rig.stores.activity.upstream(BUILDER, "codex", AT + 2)
        rig.stores.activity.label(OUTSIDER, "codex", "Elsewhere", AT)
        rig.stores.activity.label(LEAD, "claude", "Yesterday", AT - DAY)
        AsyncFileIo.drain()
        val note = "labels are samples: one per activity side query the client sends, " +
            "about every 30 seconds while a session works; a gap is a session that sent none"
        val first = """{"at":$AT,"session":"$OLD_BUILDER","slot":"b1","head":"codex",""" +
            """"label":"Running tests","detail":"row V4-131"}"""
        val second = """{"at":${AT + 1},"session":"$LEAD","slot":"lead","head":"claude",""" +
            """"label":"Reviewing","detail":null}"""
        assertEquals(
            rig.json(
                """{"team_id":"$id","day_start_epoch_millis":$DAY_START,"sample_interval_note":"$note",""" +
                    """"upstream_label_queries":1,"entries":[$first,$second]}""",
            ),
            rig.json(routes().reads.activity(id, null).body),
        )
    }

    @Test
    fun `economics joins every held session's tag, counts the untagged, and prices only what has a card`() {
        val id = rig.team().id
        val codex = rig.head(
            "codex",
            listOf(
                rig.row(AT - 5 * DAY, OLD_BUILDER, input = 1_000_000, cached = 400_000),
                rig.row(AT, BUILDER, input = 1_000_000, out = 1_000_000),
                rig.row(AT, null, input = 5),
                rig.row(AT, OUTSIDER, input = 7),
            ),
        )
        val claude = rig.head("claude", listOf(rig.row(AT, LEAD, input = 3)), rates = null)
        val body = rig.json(routes(heads = mapOf("codex" to codex, "claude" to claude)).economics(id).body)
        assertEquals("1", body.getValue("unattributed_turns").jsonPrimitive.content)
        assertEquals((AT - 5 * DAY).toString(), body.getValue("oldest_turn_epoch_millis").jsonPrimitive.content)
        assertEquals(listOf("claude", "codex"), body.getValue("heads_read").jsonArray.map { it.jsonPrimitive.content })
        val builder = rig.find(body, "roles", "role", "builder")
        assertEquals(
            rig.json(
                """{"role":"builder","turns":2,""" +
                    """"tokens":{"input":1600000,"cache_read":400000,"cache_write":0,"output":1000010},""" +
                    """"unpriced_turns":0,"last_turn_at_epoch_millis":$AT}""",
            ),
            JsonObject(builder - "cost_usd"),
        )
        // 1.6M input at 1.0, 0.4M cache reads at 0.1, 1,000,010 output at 2.0, per million.
        assertEquals(3.64002, builder.getValue("cost_usd").jsonPrimitive.double, 1e-9)
        val lead = rig.find(body, "slots", "slot", "lead")
        assertEquals("null", lead.getValue("cost_usd").toString(), "a turn with no rate card leaves no dollar figure")
        assertEquals("1", lead.getValue("unpriced_turns").jsonPrimitive.content)
        // V4-159: the lead's one row (rig.row's default outcome) is "ok", so checks reads "pass".
        assertEquals("pass", lead.getValue("checks").jsonPrimitive.content, "the lead's one row is ok")
        assertEquals(
            "the outcome tag of the slot's most recently tallied turn (PerfRow.outcome)",
            lead.getValue("checks_source").jsonPrimitive.content,
            "checks now has a real source",
        )
    }
}

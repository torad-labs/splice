// NEW: V4-402 — a day of hand-offs written before rows carried to_session, read after the sessions
// that sent them are gone. Marlin's walk of V4-391 on a home whose team had ended: 10 of 34 hand-offs
// showed, every recipient a socket path, because the registry that could name a holder had emptied.
// The fixture has that shape (4 members, 28 name-addressed rows of which 4 are subagent tool traffic,
// 10 rows to one socket, an empty registry) with synthetic ids and paths.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.activity.RecipientResolution
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path

private const val LEGACY_LEAD = "aaaaaaaa-0000-4000-8000-000000000001"
private const val LEGACY_REVIEWER = "aaaaaaaa-0000-4000-8000-000000000002"
private const val LEGACY_BUILDER = "aaaaaaaa-0000-4000-8000-000000000003"
private const val LEGACY_TESTER = "aaaaaaaa-0000-4000-8000-000000000004"
private const val LEGACY_OUTSIDER = "bbbbbbbb-0000-4000-8000-000000000009"
private const val SOCKET = "uds:/run/cc-socks/700.sock"

class LegacyHandoffsTest {
    @TempDir lateinit var tmp: Path

    private val store by lazy { TeamStore(tmp.resolve("state/teams.json"), WallClock { AT }) }
    private val stores by lazy { ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { AT }) }
    private val sessions by lazy { Files.createDirectories(tmp.resolve("sessions")) }

    private fun team(): Team {
        val id = store.upsert(
            Team(
                name = "atlas",
                goal = "ship",
                repo = Files.createDirectories(tmp.resolve("repo")).toString(),
                slots = listOf(
                    TeamSlot(id = "claude", role = "lead", head = "claude", lead = true),
                    TeamSlot(id = "gpt", role = "reviewer", head = "codex"),
                    TeamSlot(id = "grok", role = "builder", head = "grok"),
                    TeamSlot(id = "muse", role = "tester", head = "muse"),
                ),
            ),
        ).id
        return store.bind(
            id,
            mapOf(
                "claude" to LEGACY_LEAD,
                "gpt" to LEGACY_REVIEWER,
                "grok" to LEGACY_BUILDER,
                "muse" to LEGACY_TESTER,
            ),
        )
    }

    /** One legacy row: no to_session key, the way rows were written before V4-252. */
    private fun legacy(from: String, to: String, count: Int, tag: String) {
        repeat(count) { n -> stores.edges.record(MessageEdge(from, to, AT + n, "$tag-$from-$to-$n")) }
    }

    private fun register(pid: Int, session: String, name: String?, socket: String?) {
        val named = name?.let { ""","name":"$it"""" }.orEmpty()
        val at = socket?.let { ""","messagingSocketPath":"$it"""" }.orEmpty()
        Files.writeString(
            sessions.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$session","cwd":"/repo","updatedAt":$AT$at$named}""",
        )
    }

    private fun chat(team: Team): List<JsonObject> {
        AsyncFileIo.drain()
        val registry = SessionRegistry(
            sessionsDir = sessions,
            routeOf = { SessionRoute.Head("claude") },
            pidAlive = { true },
            clock = { AT },
        )
        val routes = TeamsRoutes(
            teams = TeamSource { store },
            heads = emptyMap(),
            registry = registry,
            activity = ActivitySource { stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { AT },
        )
        return Json.parseToJsonElement(routes.reads.chat(team.id, null).body)
            .jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
    }

    private fun toSlot(message: JsonObject): String? =
        message.getValue("to_slot").takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** The film's day: the lead hands 3, 9 and 4 to its three members by name, the reviewer answers the
     *  lead 6 times and the tester 2, the reviewer's subagents ('main', 'code-review') carry 4, and the
     *  builder and reviewer reach one socket 9 and 1 times. */
    private fun sepDay() {
        legacy(LEGACY_LEAD, "gpt", 3, "a")
        legacy(LEGACY_LEAD, "grok", 9, "b")
        legacy(LEGACY_LEAD, "muse", 4, "c")
        legacy(LEGACY_REVIEWER, "claude", 6, "d")
        legacy(LEGACY_TESTER, "claude", 2, "e")
        legacy(LEGACY_REVIEWER, "code-review", 2, "f")
        legacy(LEGACY_REVIEWER, "main", 2, "g")
        legacy(LEGACY_BUILDER, SOCKET, 9, "h")
        legacy(LEGACY_REVIEWER, SOCKET, 1, "i")
    }

    @Test
    fun `every non-subagent hand-off shows, name-addressed ones filed under the slot the name spells`() {
        sepDay()
        val messages = chat(team())
        assertEquals(34, messages.size, "38 stored rows less 4 subagent calls")
        val byName = messages.mapNotNull(::toSlot).groupingBy { it }.eachCount()
        assertEquals(mapOf("gpt" to 3, "grok" to 9, "muse" to 4, "claude" to 8), byName)
        assertEquals(10, messages.count { toSlot(it) == null }, "the socket rows have no recipient slot to show")
    }

    @Test
    fun `a socket held by a registry session is filed under that session's slot`() {
        sepDay()
        register(700, LEGACY_LEAD, null, "/run/cc-socks/700.sock")
        val messages = chat(team())
        assertEquals(34, messages.size)
        assertEquals(10, messages.count { toSlot(it) == "claude" && it.getValue("to").jsonPrimitive.content == SOCKET })
    }

    @Test
    fun `a name a registry session still carries is never guessed from the slot ids`() {
        legacy(LEGACY_LEAD, "gpt", 3, "a")
        register(11, LEGACY_OUTSIDER, "gpt", "/run/cc-socks/11.sock")
        assertEquals(0, chat(team()).size, "the one live holder is outside the team, so the name is that session's")
        register(12, "cccccccc-0000-4000-8000-000000000008", "gpt", "/run/cc-socks/12.sock")
        assertEquals(0, chat(team()).size, "two live holders are ambiguous, and ambiguity is not resolved by a slot id")
    }

    @Test
    fun `a sender outside the team does not reach a member by a name`() {
        legacy(LEGACY_OUTSIDER, "gpt", 3, "a")
        assertEquals(0, chat(team()).size)
    }

    @Test
    fun `an explicit no-holder is never recovered from the slot ids`() {
        stores.edges.record(MessageEdge(LEGACY_LEAD, "gpt", AT, "toolu_null", recipient = RecipientResolution.NoHolder))
        assertFalse(chat(team()).any { toSlot(it) == "gpt" })
    }
}

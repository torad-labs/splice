// NEW: V4-265 — a bound member's own turns reach its team's Activity. In a recorded four-member team
// run, the members worked for five minutes and the board read "Nothing sampled today". Claude Code
// 2.1.282 fires its "Describe your most recent action" side query only from its background-agent
// runner (AgentSummary), never for a session's own loop, so no member ever sent one. This drives a REAL daemon: a team with a bound session, two ordinary turns from that
// session after a tool call, and the team's Activity read over the control plane on the same UTC day.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.util.AsyncFileIo
import splice.head.MockChatGptUpstream
import splice.head.awaitListening
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The team's reviewer, the member on codex (an invented session id). */
private const val MEMBER = "8a2d4e6f-3c5b-4f7a-b812-1e9d0c3b5a02"

/** A member's turn after it ran the tests: the transcript every ordinary request of that session carries. */
private const val AFTER_A_TOOL_CALL =
    """[{"role":"user","content":"Review the rate limiter."},""" +
        """{"role":"assistant","content":[{"type":"text","text":"Running the suite."},""" +
        """{"type":"tool_use","id":"toolu_t1","name":"Bash","input":{"command":"cd /repo && npm test"}}]},""" +
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_t1","content":"20 passing"}]}]"""

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamActivitySampleTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private lateinit var daemon: Daemon
    private lateinit var key: String
    private lateinit var teamId: String
    private val controlPort = TestPorts.reserve()
    private val headPort = TestPorts.reserve()

    private fun topologyToml(authFile: String) = """
        [daemon]
        control_port = $controlPort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${mock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "$authFile" }
        quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true, zstd_request_body = true }

        [[providers.codex.models]]
        id = "gpt-5.6-sol"
        label = "Sol"
        context_window = 272000

        [heads.claudex]
        provider = "codex"
        port = $headPort
        discovery_prefix = "claude-codex--"
        pinned_model = "gpt-5.6-sol"
    """.trimIndent()

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("v4265-team-activity")
        val authFile = tmp.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"tok-1","account_id":"acct-1","refresh_token":"r"}}""")
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        key = MgmtKey(statePaths).get()
        val teams = ConsoleWiring.teamStore(statePaths)
        teamId = teams.upsert(
            Team(
                name = "storefront",
                goal = "Keep the storefront API working and tested.",
                repo = tmp.toString(),
                slots = listOf(TeamSlot(id = "gpt", role = "reviewer", head = "claudex")),
            ),
        ).id
        teams.bind(teamId, mapOf("gpt" to MEMBER))
        daemon = Daemon(
            topology = TopologyLoader.parse(topologyToml(authFile.toString().replace("\\", "/"))),
            statePaths = statePaths,
            dashboardHtml = { "<!doctype html><title>splice</title>" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, headPort)
    }

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        client.close()
        mock.stop()
    }

    @Test
    fun `a bound member's own turns are sampled into its team's Activity the same day, once per interval`() =
        runBlocking {
            val before = utcDay()
            repeat(2) {
                val sse = client.post("http://127.0.0.1:$headPort/v1/messages") {
                    header("Content-Type", "application/json")
                    header("Authorization", "Bearer $key")
                    header("x-claude-code-session-id", MEMBER)
                    setBody(
                        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                            "system":"You are a test. SCENARIO:basic","messages":$AFTER_A_TOOL_CALL}""",
                    )
                }.bodyAsText()
                assertTrue(sse.contains("event: message_stop"), "the turn was served: $sse")
            }
            assertTrue(AsyncFileIo.drain(), "the file lane drained")
            // The row's day and the read's day are the same UTC day unless the test straddles midnight;
            // reading both days, when they differ, makes the answer independent of that instant.
            val entries = setOf(before, utcDay()).flatMap { day -> activity(day) }
            val fields = listOf("session", "slot", "head", "label")
            assertEquals(
                listOf(listOf(MEMBER, "gpt", "claudex", "Running npm test")),
                entries.map { entry -> fields.map { entry.getValue(it).jsonPrimitive.content } },
                "one sample for the member's two turns inside one interval, under its slot",
            )
        }

    private suspend fun activity(day: LocalDate): List<JsonObject> {
        val body = client.get("http://127.0.0.1:$controlPort/api/teams/$teamId/activity?day=$day") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject.getValue("entries").jsonArray.map { it.jsonObject }
    }

    private fun utcDay(): LocalDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDate()
}

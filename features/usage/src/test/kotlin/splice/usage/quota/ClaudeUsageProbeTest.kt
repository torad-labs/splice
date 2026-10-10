// NEW: the Claude subscription usage endpoint, body fixtures and the exact GET it sends (operator ruling, Oct 4,
// 12:00 AM CT). The bodies are the shape Anthropic answers with, from three independent readers of the same
// endpoint: anthropics/claude-code#30930 (a captured 200), pleaseai/shunt src/auth/claude/usage.rs, and
// FullFran/claudeops-tui docs/oauth-usage-endpoint.md. No real credential and no real request is in this file.
package splice.usage.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.usage.FIVE_HOURS_SECONDS
import splice.core.usage.ModelQuota
import splice.core.usage.QuotaJson
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.Cancellables
import splice.core.util.WallClock

private const val NOW_MS = 1_788_000_000_000L
private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"

/** What the endpoint answered on a Max plan: both windows, a per-model weekly (Opus null, Sonnet read), and the
 *  extra-usage block (anthropics/claude-code#30930). `resets_at` carries fractional seconds and an offset. */
private const val MAX_PLAN_BODY = """
{"five_hour":{"utilization":33.0,"resets_at":"2026-04-11T07:00:00.528743+00:00"},
 "seven_day":{"utilization":13.0,"resets_at":"2026-04-17T00:59:59.951713+00:00"},
 "seven_day_opus":null,
 "seven_day_sonnet":{"utilization":1.0,"resets_at":"2026-04-16T03:00:00.951719+00:00"},
 "extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null}}
"""

class ClaudeUsageProbeTest {

    private val parser = ClaudeUsageParser()
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `both windows are read with their own lengths and their resets in epoch seconds`() {
        val snapshot = parser.parse(obj(MAX_PLAN_BODY), NOW_MS)!!

        assertEquals(33.0, snapshot.fiveHour!!.usedPercent, 1e-9)
        assertEquals(FIVE_HOURS_SECONDS, snapshot.fiveHour!!.windowSeconds)
        assertEquals(1_775_890_801L, snapshot.fiveHour!!.resetsAt, "2026-04-11T07:00:01Z")
        assertEquals(13.0, snapshot.sevenDay!!.usedPercent, 1e-9)
        assertEquals(SEVEN_DAYS_SECONDS, snapshot.sevenDay!!.windowSeconds)
        assertEquals(1_776_387_600L, snapshot.sevenDay!!.resetsAt, "2026-04-17T01:00:00Z")
        assertEquals(NOW_MS, snapshot.updatedAt)
    }

    // Oct 10, 2026: Accounts draws each model's weekly window under the week. A model the body reports null has none.
    @Test
    fun `each model's weekly window is read by the name a person reads, and a null one is no window`() {
        val snapshot = parser.parse(obj(MAX_PLAN_BODY), NOW_MS)!!

        assertEquals(listOf(ModelQuota("Sonnet", 1.0)), snapshot.models)
    }

    // A turn's headers name no model weeks: the reading keeps the last ones while it is the same week, and drops them
    // once the week rolls.
    @Test
    fun `a reading with no model weeks keeps the last ones for the same week only`() {
        val probed = parser.parse(obj(MAX_PLAN_BODY), NOW_MS)!!
        val week = probed.sevenDay!!
        val sameWeek = probed.copy(models = emptyList(), sevenDay = week.copy(resetsAt = week.resetsAt!! + 1))
        val rolled = week.copy(resetsAt = week.resetsAt!! + SEVEN_DAYS_SECONDS)
        val nextWeek = probed.copy(models = emptyList(), sevenDay = rolled)

        assertEquals(probed.models, sameWeek.keepingModelsOf(probed).models)
        assertEquals(emptyList<ModelQuota>(), nextWeek.keepingModelsOf(probed).models)
        assertEquals(probed.models, QuotaJson().decode(QuotaJson().encode(probed))!!.models, "kept across a restart")
    }

    @Test
    fun `fractional reset seconds round at the boundary instead of disagreeing about the reset minute`() {
        val readings = listOf(
            "2026-04-17T00:59:59.49Z" to 1_776_387_599L,
            "2026-04-17T00:59:59.50Z" to 1_776_387_600L,
            "2026-04-17T00:59:59.951713Z" to 1_776_387_600L,
            "2026-04-17T01:59:59.95+01:00" to 1_776_387_600L,
            "2026-04-17T01:00:00Z" to 1_776_387_600L,
        )
        for ((reset, expected) in readings) {
            val snapshot = parser.parse(
                obj("""{"seven_day":{"utilization":98.0,"resets_at":"$reset"}}"""),
                NOW_MS,
            )!!
            assertEquals(expected, snapshot.sevenDay!!.resetsAt, reset)
        }
    }

    @Test
    fun `a window the body reports as null is no window, and one with no reset still reports its utilization`() {
        val snapshot = parser.parse(obj("""{"five_hour":null,"seven_day":{"utilization":88.0}}"""), NOW_MS)!!

        assertNull(snapshot.fiveHour, "a null window is absent, never a zero bar")
        assertEquals(88.0, snapshot.sevenDay!!.usedPercent, 1e-9)
        assertNull(snapshot.sevenDay!!.resetsAt)
    }

    @Test
    fun `a body that names no window at all is nothing to record`() {
        assertNull(parser.parse(obj("""{"extra_usage":{"is_enabled":false}}"""), NOW_MS))
    }

    @Test
    fun `the GET carries the account's own bearer, the oauth beta header and the client's User-Agent`() = runTest {
        val sent = mutableListOf<Map<String, String>>()
        val probe = probe(sent, userAgent = "claude-cli/2.1.289 (external, cli)")

        probe.probe()

        val headers = sent.single()
        assertEquals("Bearer added-account-token", headers["Authorization"])
        assertEquals("oauth-2025-04-20", headers["anthropic-beta"])
        assertEquals("claude-cli/2.1.289 (external, cli)", headers["User-Agent"])
    }

    @Test
    fun `a head that has seen no client sends no User-Agent of its own`() = runTest {
        val sent = mutableListOf<Map<String, String>>()

        probe(sent, userAgent = null).probe()

        assertTrue(sent.single()["User-Agent"].isNullOrEmpty(), "splice names no client it has not seen")
    }

    @Test
    fun `a refusal names its status rather than freezing the bars with no line`() = runTest {
        val probe = probe(mutableListOf(), status = HttpStatusCode.TooManyRequests)

        val refused = runCatching { probe.probe() }.exceptionOrNull()

        assertTrue(refused is QuotaEndpointRefused, "a non-200 is reported, never swallowed: $refused")
        assertEquals(429, (refused as QuotaEndpointRefused).status)
    }

    @Test
    fun `probe refusals cannot be decoded as full windows even when the body contains them`() = runTest {
        val full = """{"five_hour":{"utilization":100},"seven_day":{"utilization":100}}"""
        val statuses = listOf(
            HttpStatusCode.Forbidden,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.InternalServerError,
        )
        for (status in statuses) {
            val probe = probe(mutableListOf(), status = status, body = full)
            val refused = Cancellables.runCatchingCancellable { probe.probe() }.exceptionOrNull()
            assertTrue(refused is QuotaEndpointRefused, "a refusal is not a usage observation")
            assertEquals(status.value, (refused as QuotaEndpointRefused).status)
        }
        assertNull(
            probe(mutableListOf(), body = """{"error":{"utilization":100,"type":"rate_limit_error"}}""").probe(),
            "an error-only 200 body has no quota windows",
        )
    }

    @Test
    fun `the caller's own forwarded sign-in is never probed`() = runTest {
        val sent = mutableListOf<Map<String, String>>()

        val snapshot = probe(sent, credentials = Credentials.ClientForwarded).probe()

        assertNull(snapshot, "splice holds no credential for the caller, so it asks nothing on its behalf")
        assertTrue(sent.isEmpty(), "and sends no request at all")
    }

    private fun probe(
        sent: MutableList<Map<String, String>>,
        userAgent: String? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        credentials: Credentials? = Credentials.Bearer("added-account-token"),
        body: String = MAX_PLAN_BODY,
    ): QuotaProbe {
        val engine = MockEngine { request ->
            sent += request.headers.entries().associate { it.key to it.value.joinToString(", ") }
            assertEquals(USAGE_URL, request.url.toString())
            respond(body, status, headersOf("Content-Type", "application/json"))
        }
        return ClaudeUsageProbe(
            client = HttpClient(engine),
            auth = StubAuth(credentials),
            userAgent = ClientUserAgent { userAgent },
            clock = WallClock { NOW_MS },
        )
    }

    private class StubAuth(private val credentials: Credentials?) : AuthProvider {
        override suspend fun credentials(): Credentials? = credentials
        override suspend fun describe(): AuthDescription = AuthDescription(present = credentials != null, kind = "stub")
    }
}

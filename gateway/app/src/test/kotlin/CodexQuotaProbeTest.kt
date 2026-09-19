// NEW: Codex usage body fixtures (live 2026-09-02) and the exact GET header map.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.quota.CodexQuotaParser
import splice.app.quota.CodexQuotaProbe
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.util.WallClock

class CodexQuotaProbeTest {

    private val parser = CodexQuotaParser()
    private val now = 1_788_000_000_000L
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `codex Pro reports one weekly window called primary, and it lands in the 7d slot`() {
        val body = obj(
            """{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false,
               "primary_window":{"used_percent":30,"limit_window_seconds":604800,"reset_after_seconds":497935,"reset_at":1788855387},
               "secondary_window":null}}""",
        )
        val s = parser.parse(body, now)!!
        assertNull(s.fiveHour)
        assertEquals(30.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals(1_788_855_387L, s.sevenDay!!.resetsAt)
        assertEquals("pro", s.plan)
    }

    @Test
    fun `codex Plus reports both windows`() {
        val body = obj(
            """{"plan_type":"plus","rate_limit":{
               "primary_window":{"used_percent":40,"limit_window_seconds":18000,"reset_after_seconds":5880},
               "secondary_window":{"used_percent":21,"limit_window_seconds":604800,"reset_at":1788500000}}}""",
        )
        val s = parser.parse(body, now)!!
        assertEquals(40.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(now / 1000 + 5880, s.fiveHour!!.resetsAt)
        assertEquals(21.0, s.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `codex used_percent is not clamped`() {
        val body = obj(
            """{"plan_type":"plus","rate_limit":{
               "primary_window":{"used_percent":150,"limit_window_seconds":18000},
               "secondary_window":{"used_percent":-5,"limit_window_seconds":604800}}}""",
        )
        val s = parser.parse(body, now)!!
        assertEquals(150.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(-5.0, s.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `codex body reset_at at 1e11 and above is milliseconds`() {
        val body = obj(
            """{"plan_type":"plus","rate_limit":{
               "primary_window":{"used_percent":1,"limit_window_seconds":18000,"reset_at":100000000001}}}""",
        )
        assertEquals(100_000_000L, parser.parse(body, now)!!.fiveHour!!.resetsAt)
    }

    @Test
    fun `codex GET sends Authorization then Accept then ChatGPT-Account-Id and no xAI headers`() = runTest {
        var captured: Map<String, String>? = null
        var order: List<String>? = null
        val body = """{"rate_limit":{"primary_window":{"used_percent":1,"limit_window_seconds":18000}}}"""
        val engine = MockEngine { request ->
            captured = probeHeaders(request.headers)
            order = request.headers.entries().map { it.key }
            respond(content = body, status = HttpStatusCode.OK)
        }
        val probe = CodexQuotaProbe(
            HttpClient(engine),
            "https://chatgpt.com/backend-api/codex",
            FixedAuth(Credentials.Bearer("tok", accountId = "acct-1")),
            WallClock { now },
        )
        probe.probe()
        assertEquals(
            mapOf(
                "Authorization" to "Bearer tok",
                "Accept" to "application/json",
                "ChatGPT-Account-Id" to "acct-1",
            ),
            captured,
        )
        val iAuth = order!!.indexOfFirst { it.equals("Authorization", ignoreCase = true) }
        val iAccept = order!!.indexOfFirst { it.equals("Accept", ignoreCase = true) }
        val iAcct = order!!.indexOfFirst { it.equals("ChatGPT-Account-Id", ignoreCase = true) }
        assertTrue(iAuth >= 0 && iAccept > iAuth && iAcct > iAccept)
    }

    private fun probeHeaders(headers: io.ktor.http.Headers): Map<String, String> {
        val names = listOf(
            "Authorization",
            "Accept",
            "ChatGPT-Account-Id",
            "x-api-key",
            "x-grok-client-mode",
            "x-grok-client-version",
            "X-XAI-Token-Auth",
        )
        return names.mapNotNull { n -> headers[n]?.let { n to it } }.toMap()
    }

    private class FixedAuth(private val creds: Credentials?) : AuthProvider {
        override suspend fun credentials(): Credentials? = creds
        override suspend fun describe(): AuthDescription = AuthDescription(creds != null, "test")
    }
}

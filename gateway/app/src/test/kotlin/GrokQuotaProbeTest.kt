// NEW: SuperGrok billing body fixture (live 2026-09-02) and the exact GET header map including xAI.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.quota.GrokQuotaParser
import splice.app.quota.GrokQuotaProbe
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.util.WallClock

class GrokQuotaProbeTest {

    private val parser = GrokQuotaParser()
    private val now = 1_788_000_000_000L
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `supergrok reports one weekly period`() {
        val body = obj(
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-09-01T09:48:15.732924+00:00","end":"2026-09-08T09:48:15.732924+00:00"},
               "creditUsagePercent":3.0,"productUsage":[{"product":"GrokBuild","usagePercent":3.0}]}}""",
        )
        val s = parser.parse(body, now)!!
        assertNull(s.fiveHour)
        assertEquals(3.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals(1_788_860_895L, s.sevenDay!!.resetsAt)
        assertNull(parser.parse(obj("""{"error":"nope"}"""), now))
    }

    @Test
    fun `grok used_percent is not clamped`() {
        assertEquals(
            150.0,
            parser.parse(
                obj("""{"config":{"creditUsagePercent":150.0}}"""),
                now,
            )!!.sevenDay!!.usedPercent,
            1e-9,
        )
        assertEquals(
            -5.0,
            parser.parse(
                obj("""{"config":{"creditUsagePercent":-5.0}}"""),
                now,
            )!!.sevenDay!!.usedPercent,
            1e-9,
        )
    }

    @Test
    fun `a malformed period end is ignored`() {
        val s = parser.parse(
            obj(
                """{"config":{"creditUsagePercent":3.0,
                   "currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","end":"not-a-date"}}}""",
            ),
            now,
        )!!
        assertEquals(3.0, s.sevenDay!!.usedPercent, 1e-9)
        assertNull(s.sevenDay!!.resetsAt)
    }

    @Test
    fun `grok GET sends Accept then the three xAI headers`() = runTest {
        var captured: Map<String, String>? = null
        val engine = MockEngine { request ->
            captured = probeHeaders(request.headers)
            respond(content = """{"config":{"creditUsagePercent":1.0}}""", status = HttpStatusCode.OK)
        }
        val probe = GrokQuotaProbe(
            HttpClient(engine),
            FixedAuth(Credentials.Bearer("tok")),
            WallClock { now },
        )
        probe.probe()
        assertEquals(
            mapOf(
                "Authorization" to "Bearer tok",
                "Accept" to "application/json",
                "x-grok-client-mode" to "cli",
                "x-grok-client-version" to "0.2.93",
                "X-XAI-Token-Auth" to "xai-grok-cli",
            ),
            captured,
        )
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

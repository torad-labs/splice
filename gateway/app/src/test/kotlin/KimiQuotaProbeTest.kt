// NEW: Kimi usage body fixture (live 2026-09-02) and the exact GET header map — no xAI headers.
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
import splice.app.quota.KimiQuotaParser
import splice.app.quota.KimiQuotaProbe
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.util.WallClock

class KimiQuotaProbeTest {

    private val parser = KimiQuotaParser()
    private val now = 1_788_000_000_000L
    private val kimiBody =
        """{"user":{"membership":{"level":"LEVEL_STANDARD"}},
           "usage":{"limit":"100","remaining":"74","resetTime":"2026-09-05T04:17:14.476605Z"},
           "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                      "detail":{"limit":"100","remaining":"85","resetTime":"2026-09-02T15:17:14.476605Z"}}]}"""

    @Test
    fun `kimi reports the weekly quota and a 300-minute rate window as strings`() {
        val s = parser.parse(Json.parseToJsonElement(kimiBody).jsonObject, now)!!
        assertEquals(15.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(1_788_362_234L, s.fiveHour!!.resetsAt)
        assertEquals(26.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals("standard", s.plan)
    }

    @Test
    fun `a malformed resetTime is ignored`() {
        val body = Json.parseToJsonElement(
            """{"usage":{"limit":"100","remaining":"50","resetTime":"not-a-date"},
               "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                          "detail":{"limit":"100","remaining":"50","resetTime":"also-bad"}}]}""",
        ).jsonObject
        val s = parser.parse(body, now)!!
        assertNull(s.fiveHour!!.resetsAt)
        assertNull(s.sevenDay!!.resetsAt)
    }

    @Test
    fun `kimi api-key GET sends x-api-key and Accept and no xAI headers`() = runTest {
        var captured: Map<String, String>? = null
        val engine = MockEngine { request ->
            captured = probeHeaders(request.headers)
            respond(content = kimiBody, status = HttpStatusCode.OK)
        }
        val probe = KimiQuotaProbe(
            HttpClient(engine),
            "https://api.kimi.com/coding",
            FixedAuth(Credentials.ApiKey(key = "kimi-secret", header = "x-api-key", prefix = "")),
            WallClock { now },
        )
        val snapshot = probe.probe()
        assertEquals(15.0, snapshot!!.fiveHour!!.usedPercent, 1e-9)
        assertEquals(mapOf("x-api-key" to "kimi-secret", "Accept" to "application/json"), captured)
        assertNull(captured!!["Authorization"])
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

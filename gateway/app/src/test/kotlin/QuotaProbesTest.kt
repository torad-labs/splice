// NEW: kimi OAuth yields Credentials.ApiKey(x-api-key); the old Bearer-only probe recorded nothing.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.quota.BearerGetProbe
import splice.app.quota.QUOTA_POLL_INTERVAL_MS
import splice.app.quota.QuotaParsers
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.util.WallClock

class QuotaProbesTest {

    private val now = 1_788_000_000_000L
    private val kimiBody =
        """{"user":{"membership":{"level":"LEVEL_STANDARD"}},
           "usage":{"limit":"100","remaining":"74","resetTime":"2026-09-05T04:17:14.476605Z"},
           "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                      "detail":{"limit":"100","remaining":"85","resetTime":"2026-09-02T15:17:14.476605Z"}}]}"""

    private class FixedAuth(private val creds: Credentials?) : AuthProvider {
        override suspend fun credentials(): Credentials? = creds
        override suspend fun describe(): AuthDescription = AuthDescription(creds != null, "test")
    }

    @Test
    fun `kimi api-key credentials send x-api-key and parse usage`() = runTest {
        var apiKeyHeader: String? = null
        var authorization: String? = null
        val engine = MockEngine { request ->
            apiKeyHeader = request.headers["x-api-key"]
            authorization = request.headers["Authorization"]
            respond(kimiBody, HttpStatusCode.OK)
        }
        val probe = BearerGetProbe(
            client = HttpClient(engine),
            url = "https://api.example.test/v1/usages",
            auth = FixedAuth(Credentials.ApiKey(key = "kimi-secret", header = "x-api-key", prefix = "")),
            parse = QuotaParseAdapter(),
            clock = WallClock { now },
        )
        val snapshot = probe.probe()
        assertNotNull(snapshot, "ApiKey credentials must not be dropped the way Bearer-only probing did")
        assertEquals("kimi-secret", apiKeyHeader)
        assertNull(authorization)
        assertEquals(15.0, snapshot!!.fiveHour!!.usedPercent, 1e-9)
        assertEquals(26.0, snapshot.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `bearer credentials still send Authorization`() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers["Authorization"]
            respond(kimiBody, HttpStatusCode.OK)
        }
        val probe = BearerGetProbe(
            client = HttpClient(engine),
            url = "https://api.example.test/v1/usages",
            auth = FixedAuth(Credentials.Bearer("tok")),
            parse = QuotaParseAdapter(),
            clock = WallClock { now },
        )
        assertNotNull(probe.probe())
        assertEquals("Bearer tok", authorization)
    }

    @Test
    fun `quota poller cadence is five minutes`() {
        assertEquals(5 * 60 * 1000L, QUOTA_POLL_INTERVAL_MS)
    }

    private class QuotaParseAdapter : splice.app.quota.QuotaParse {
        private val parsers = QuotaParsers()
        override fun parse(body: kotlinx.serialization.json.JsonObject, now: Long) = parsers.kimi(body, now)
    }
}

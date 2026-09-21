// NEW: forHead dispatch per auth kind, shared Bearer GET, and the five-minute poller cadence.
package splice.app.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.ProviderBuild
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.core.util.WallClock
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun `BearerGetProbe extraHeaders default carries no vendor headers`() = runTest {
        var accept: String? = null
        var extras: Map<String, String>? = null
        val engine = MockEngine { request ->
            accept = request.headers["Accept"]
            extras = listOf(
                "x-grok-client-mode",
                "x-grok-client-version",
                "X-XAI-Token-Auth",
            ).mapNotNull { n -> request.headers[n]?.let { n to it } }.toMap()
            respond("{}", HttpStatusCode.OK)
        }
        BearerGetProbe(
            client = HttpClient(engine),
            url = "https://api.example.test/usage",
            auth = FixedAuth(Credentials.Bearer("tok")),
            parse = QuotaParseAdapter(),
            clock = WallClock { now },
        ).probe()
        assertEquals("*/*", accept)
        assertEquals(emptyMap<String, String>(), extras)
    }

    @Test
    fun `forHead dispatches one probe class per auth kind`(@TempDir tmp: Path) {
        val probes = QuotaProbes(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }))
        val auth = FixedAuth(Credentials.Bearer("tok"))
        assertTrue(probes.forHead(ctx(tmp, "chatgpt-oauth"), auth, null) is CodexQuotaProbe)
        assertTrue(probes.forHead(ctx(tmp, "kimi-oauth"), auth, null) is KimiQuotaProbe)
        assertTrue(probes.forHead(ctx(tmp, "grok-oauth"), auth, null) is GrokQuotaProbe)
        assertNull(probes.forHead(ctx(tmp, "muse-oauth"), auth, null), "muse without UsageFields is refused")
        val fields = UsageFields { null }
        assertTrue(probes.forHead(ctx(tmp, "muse-oauth"), auth, fields) is MuseMintProbe)
        assertNull(probes.forHead(ctx(tmp, "api-key"), auth, null))
    }

    @Test
    fun `forHead kimi and codex probes carry no xAI headers`(@TempDir tmp: Path) = runTest {
        val captured = mutableListOf<Map<String, String>>()
        val engine = MockEngine { request ->
            val names = listOf("x-grok-client-mode", "x-grok-client-version", "X-XAI-Token-Auth")
            captured += names.mapNotNull { n -> request.headers[n]?.let { n to it } }.toMap()
            respond("{}", HttpStatusCode.OK)
        }
        val probes = QuotaProbes(HttpClient(engine))
        val auth = FixedAuth(Credentials.Bearer("tok"))
        probes.forHead(ctx(tmp, "chatgpt-oauth"), auth, null)!!.probe()
        probes.forHead(ctx(tmp, "kimi-oauth"), auth, null)!!.probe()
        assertEquals(2, captured.size)
        assertTrue(captured.all { it.isEmpty() })
    }

    private fun ctx(tmp: Path, kind: String): ProviderBuild {
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(state, envReader = { null })
        return ProviderBuild(
            key = "head",
            head = HeadConfig(provider = "p", port = 3100, discoveryPrefix = "claude-p--", pinnedModel = "m"),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://api.example.test/backend-api/codex",
                auth = AuthConfig(kind = kind),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-p--",
                models = listOf(ModelEntry(id = "m", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = config.getConfig("head"),
            loginCommand = "login",
        )
    }

    private class QuotaParseAdapter : QuotaParse {
        private val parsers = KimiQuotaParser()
        override fun parse(body: kotlinx.serialization.json.JsonObject, now: Long) = parsers.parse(body, now)
    }
}

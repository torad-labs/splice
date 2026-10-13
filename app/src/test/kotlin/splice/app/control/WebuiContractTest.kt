// The /api/* payload contract (P4-WEBUI). The field lists were transcribed from the console's
// types, which left the repo with the console UI on Oct 7, 2026; they still pin the payloads the
// API serves. This test boots ControlServer with a stub head and pins the required fields of the
// payload types it exercises, plus selected optional fields its fixture supplies.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.economics.EconomicsBytes
import splice.usage.economics.EconomicsCost
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import splice.usage.economics.EconomicsTokens
import splice.usage.economics.EconomicsTools
import splice.usage.economics.EconomicsTurnCounts
import splice.usage.economics.HeadEconomicsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Path

private class ContractHead(override val key: String, override val port: Int) : Head {
    override val label = key
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = port, version = "kt-1")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebuiContractTest {

    private val client = HttpClient(CIO)

    // OSS-M: fixed test ports lived in the Linux ephemeral range — transient outbound source ports
    // collide at bind time on busy hosts. The server binds port 0 and reports what it got
    // (2026-09-23): a port leased with ServerSocket(0) and bound later could be taken in between. No
    // readiness poll: ControlServer.start returns routed and bound (Ktor's default SEQUENTIAL startup
    // runs the modules before NettyApplicationEngine's bind(...).sync(); V4-139).
    private val port: Int get() = control.listeningPort
    private lateinit var key: String
    private lateinit var control: ControlServer
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val managed = ManagedHead(
            head = ContractHead("codex", 3099),
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() =
                    AuthDescription(true, "chatgpt-oauth", mapOf("account_ref" to "1a2b3c4d"))
            },
            sources = HeadSources(
                usage = object : HeadUsageSource {
                    override fun snapshot() = UsageView(0L, 1, RateLimitView(1000, 100, "6m0s"))
                },
                compact = object : HeadCompactSource {
                    override fun summary(tailN: Int) =
                        CompactView(
                            1,
                            mapOf("model_text" to 1),
                            listOf(mapOf("ts" to "1000", "outcome" to "model_text", "chars" to "42", "ms" to "12")),
                        )
                },
                logs = object : HeadLogSource {
                    override fun tail(lines: Int) = "[codex] line one\n[codex] line two\n"
                    override fun path() = "/tmp/codex.log"
                },
                economics = HeadEconomicsSource {
                    EconomicsRead.Rows(
                        listOf(
                            EconomicsRow(
                                1_000,
                                EconomicsTurnCounts(2, 1),
                                EconomicsTokens(300, 270, 24, 5),
                                EconomicsBytes(400, 440),
                                EconomicsTools(28, 48, 2),
                                1,
                                EconomicsCost(costUsd = 0.42, unpricedTurns = 1),
                            ),
                        ),
                    )
                },
            ),
            usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
            authSurface = HeadAuthSurface(authKind = "chatgpt-oauth"),
        )
        control = controlServerFor(
            port = 0,
            heads = mapOf("codex" to managed),
            config = ConfigService(paths),
            auth = ControlAuth(mgmtKey = mgmt, log = {}),
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() = runBlocking {
        control.stop()
        client.close()
    }

    private suspend fun api(path: String): JsonObject =
        json.parseToJsonElement(
            client.get("http://127.0.0.1:$port$path") { header("Authorization", "Bearer $key") }.bodyAsText(),
        ).jsonObject

    private fun assertFields(obj: JsonObject, fields: List<String>, where: String) {
        val missing = fields.filter { it !in obj.keys }
        assertTrue(missing.isEmpty(), "$where missing webui-contract fields: $missing (has ${obj.keys})")
    }

    @Test
    fun `heads payload matches HeadsPayload plus HeadStatus`() = runBlocking {
        val payload = api("/api/heads")
        assertFields(payload, listOf("heads"), "HeadsPayload")
        val head = payload["heads"]!!.jsonArray.first().jsonObject
        assertFields(
            head,
            // HeadStatus (console-next/src/types/core.ts) — gate/mode/maxInflight are contract-nullable.
            listOf(
                "key", "label", "name", "port", "authKind", "wantVersion",
                "running", "healthy", "version", "versionMatch", "mode", "gate", "maxInflight", "health", "pids",
            ),
            "HeadStatus",
        )
        assertFields(head["health"]!!.jsonObject, listOf("localOriginErrors", "providerErrors"), "HeadHealthCounters")
    }

    @Test
    fun `config payload matches ConfigPayload`() = runBlocking {
        // ConfigPayload (console-next/src/types/core.ts); the successor reads per-head layers too.
        val payload = api("/api/config")
        assertFields(payload, listOf("effective", "layers", "restart_required_keys"), "ConfigPayload")
        assertFields(
            payload["layers"]!!.jsonObject,
            listOf("defaults", "toml", "perHead", "file", "env", "runtime"),
            "ConfigPayload.layers",
        )
    }

    @Test
    fun `usage payload matches UsagePayload plus nested HeadUsage`() = runBlocking {
        val payload = api("/api/usage")
        assertFields(payload, listOf("heads"), "UsagePayload")
        val head = payload["heads"]!!.jsonArray.first().jsonObject
        assertFields(head, listOf("key", "label", "usage"), "HeadUsageEntry")
        val usage = head["usage"]!!.jsonObject
        assertFields(usage, listOf("output_tokens_5h", "entries", "ratelimit", "warn"), "HeadUsage")
        assertFields(
            usage["ratelimit"]!!.jsonObject,
            listOf("limit_tokens", "remaining_tokens", "reset_tokens"),
            "RatelimitState",
        )
        assertFields(usage["warn"]!!.jsonObject, listOf("level", "pct", "source", "reset"), "UsageWarn")
    }

    @Test
    fun `compact payload matches CompactPayload plus CompactRow`() = runBlocking {
        val payload = api("/api/compact")
        assertFields(payload, listOf("stats"), "CompactPayload")
        val stats = payload["stats"]!!.jsonObject
        assertFields(stats, listOf("total", "by_outcome", "by_outcome_7d", "heads", "tail"), "CompactPayload.stats")
        assertFields(
            stats["tail"]!!.jsonArray.first().jsonObject,
            listOf("head", "ts", "outcome", "chars", "ms"),
            "CompactRow",
        )
    }

    @Test
    fun `auth payload matches AuthPayload plus ProviderAuth`() = runBlocking {
        // AuthPayload is keyed by head; this fixture supplies the codex ProviderAuth.
        val payload = api("/api/auth")
        assertFields(payload, listOf("codex"), "AuthPayload")
        assertFields(
            payload["codex"]!!.jsonObject,
            // V4-220 item 6b: `verdict` ({state, at_epoch_ms?}) is on every head's auth object.
            listOf("present", "verdict"),
            "ProviderAuth",
        )
    }

    @Test
    fun `logs payload matches LogsPayload`() = runBlocking {
        assertFields(api("/api/logs/codex"), listOf("key", "path", "lines"), "LogsPayload")
    }

    /** ClaudeHeadPayload (console-next/src/types/claude-head.ts). ADDED BY V4-175, and it is
     *  the route that proves why this wall's route list has to be the denominator rather than a
     *  sample: /api/claude-head was absent from it, so the webui's types were written AHEAD of the
     *  daemon ("PENDING V4-129") and V4-129 then shipped a different payload. Nothing compared the
     *  two, and the settings page has been reading `head`, `config_dir` and `claude_on_path` off a
     *  response that never carried them — rendering two blanks and, worse, printing "nothing named
     *  claude" from an absent field while the daemon knew the answer under another name. */
    @Test
    fun `claude-head payload matches ClaudeHeadPayload`() = runBlocking {
        val payload = api("/api/claude-head")
        assertFields(
            payload,
            listOf("mode", "shim_path"),
            "ClaudeHeadPayload",
        )
        // The one VALUE this wall pins, because a name check could not have caught it: the webui's
        // CLAUDE_HEAD_MODES is the closed set the settings page branches on, and it read `wrap`
        // against a daemon that says `wrapped`, so `mode === 'wrap'` was never true and a WRAPPED
        // machine rendered the separate badge, the separate sentence and a Wrap button. Both
        // spellings are present on every field list; only the value tells them apart.
        val mode = payload["mode"]!!.jsonPrimitive.content
        assertTrue(
            mode in listOf("separate", "wrapped"),
            "mode '$mode' is outside webui CLAUDE_HEAD_MODES — the page would render the other branch",
        )
    }

    /** /api/economics carries what the Accounts page reads (Oct 12: every bucket sum nothing read was deleted). */
    @Test
    fun `economics payload carries the heads and their buckets`() = runBlocking {
        val payload = api("/api/economics")
        assertFields(payload, listOf(HEADS_KEY), "EconomicsPayload")
        val head = payload[HEADS_KEY]!!.jsonArray.first().jsonObject
        assertFields(head, listOf("key", "buckets"), "HeadEconomics")
        val bucket = head["buckets"]!!.jsonArray.first().jsonObject
        assertFields(bucket, listOf("hour", "turns", "cost_usd"), "EconomicsBucket")
    }
}

private const val HEADS_KEY = "heads"

// NEW: the webui contract gate (P4-WEBUI). The unmodified React dashboard consumes the daemon's
// /api/* JSON through the field names declared in webui/src/shared/api/index.ts. This test boots
// the ControlServer with a stub head and asserts every declared field is present in the daemon's
// actual JSON — so a rename in the Kotlin payload builders breaks THIS test, not the dashboard at
// runtime. Field sets are transcribed from index.ts @ pre-public-port-baseline (the comment is the source of
// truth; a drift shows up as a failing assertion here). Manual click-through stays operator work;
// this pins the SHAPE contract automatically.
package splice.control

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
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

private class ContractHead(override val key: String, override val port: Int) : Head {
    override val label = key
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = port, version = "kt-1")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebuiContractTest {

    private val client = HttpClient(CIO)
    private val port = freshPort()
    private lateinit var key: String
    private lateinit var control: ControlServer
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("contract")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val managed = ManagedHead(
            head = ContractHead("codex", 3099),
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() =
                    AuthDescription(true, "chatgpt-oauth", mapOf("account_id_masked" to "acct…5678"))
            },
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
                listOf(EconomicsRow(1_000, 2, 300, 270, 24, 5, 400, 440, 28, 48, 2, 1))
            },
            warnPct = 80,
            warnTokens5h = 0,
            authKind = "chatgpt-oauth",
        )
        control = ControlServer(
            port = port,
            heads = mapOf("codex" to managed),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = {},
        )
        runBlocking { control.start() }
        awaitListening(port)
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
            // HeadStatus (server/launcher/heads.mjs) — gate/mode/maxInflight are contract-nullable.
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
        // Node returns {effective, layers:{defaults,file,env,runtime}, restart_required_keys, source}
        // (server/src/control/api.mjs:73). The webui reads the layer objects UNDER `layers`.
        val payload = api("/api/config")
        assertFields(payload, listOf("effective", "layers", "restart_required_keys"), "ConfigPayload")
        assertFields(
            payload["layers"]!!.jsonObject,
            listOf("defaults", "toml", "file", "env", "runtime"),
            "ConfigPayload.layers",
        )
    }

    @Test
    fun `usage payload matches UsagePayload plus nested HeadUsage`() = runBlocking {
        val payload = api("/api/usage")
        assertFields(payload, listOf("window_hours", "warn_pct", "warn_tokens_5h", "heads"), "UsagePayload")
        val head = payload["heads"]!!.jsonArray.first().jsonObject
        assertFields(head, listOf("key", "label", "usage"), "HeadUsageEntry")
        assertFields(head["usage"]!!.jsonObject, listOf("output_tokens_5h", "entries", "warn"), "HeadUsage")
    }

    @Test
    fun `compact payload matches CompactPayload plus CompactRow`() = runBlocking {
        val payload = api("/api/compact")
        assertFields(payload, listOf("stats"), "CompactPayload")
        val stats = payload["stats"]!!.jsonObject
        assertFields(stats, listOf("total", "by_outcome", "tail"), "CompactPayload.stats")
        assertFields(
            stats["tail"]!!.jsonArray.first().jsonObject,
            listOf("head", "ts", "outcome", "chars", "ms"),
            "CompactRow",
        )
    }

    @Test
    fun `auth payload matches AuthPayload plus CodexAuth`() = runBlocking {
        // Node keys auth by head; webui reads `.codex` (server/src/control/api.mjs:130).
        val payload = api("/api/auth")
        assertFields(payload, listOf("codex"), "AuthPayload")
        assertFields(
            payload["codex"]!!.jsonObject,
            listOf("kind", "present", "login", "account_id_masked"),
            "CodexAuth",
        )
    }

    @Test
    fun `logs payload matches LogsPayload`() = runBlocking {
        assertFields(api("/api/logs/codex"), listOf("key", "path", "lines"), "LogsPayload")
    }

    /** EconomicsPayload + HeadEconomics + EconomicsBucket (webui shared/api). The bucket fields
     *  are the burn page's whole input; a rename here silently blanks the quota gauge, which is
     *  the one surface whose failure mode is reading SAFE while the plan drains. */
    @Test
    fun `economics payload matches EconomicsPayload plus nested bucket`() = runBlocking {
        val payload = api("/api/economics")
        assertFields(payload, listOf("retention_hours", "generated_at", HEADS_KEY), "EconomicsPayload")
        val head = payload[HEADS_KEY]!!.jsonArray.first().jsonObject
        assertFields(head, listOf("key", "label", "ceiling_tokens", "buckets"), "HeadEconomics")
        assertFields(
            head["buckets"]!!.jsonArray.first().jsonObject,
            listOf(
                "hour", "turns", "in_tokens", "cached_tokens", "cache_write_tokens", "out_tokens",
                "req_bytes", "upstream_req_bytes",
                "tools_eager", "tools_deferred", "deferral_turns", "rate_limited",
            ),
            "EconomicsBucket",
        )
    }

    /** The page bills on TOTAL input, so in_tokens and cached_tokens must stay SEPARATE fields.
     *  Pre-summing them upstream (or shipping only the uncached remainder) is the exact mistake
     *  that made a 90%-cached drain look safe — the wire must carry both, unreduced. */
    @Test
    fun `economics ships input and cached separately, not pre-netted`() = runBlocking {
        val bucket = api("/api/economics")[HEADS_KEY]!!.jsonArray.first().jsonObject["buckets"]!!
            .jsonArray.first().jsonObject
        assertEquals(300L, bucket["in_tokens"]!!.jsonPrimitive.long, "in_tokens is the METERED total")
        assertEquals(270L, bucket["cached_tokens"]!!.jsonPrimitive.long, "cached is reported, never subtracted")
        // V4-86: the cache-WRITE half is a THIRD separate field. Netting it into either of the
        // other two would hide the one bucket that bills at its own rate, and pre-summing the two
        // cache buckets would make a read and a write indistinguishable on the page.
        assertEquals(
            24L,
            bucket["cache_write_tokens"]!!.jsonPrimitive.long,
            "the cache-write bucket reaches the wire on its own, disjoint from cached_tokens",
        )
    }
}

private const val HEADS_KEY = "heads"

// OSS-M: fixed test ports lived in the Linux ephemeral range — transient outbound source ports
// collide at bind time on busy hosts; ports are OS-assigned and readiness is polled, not slept.
private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

private fun awaitListening(vararg ports: Int) {
    for (p in ports) awaitOne(p)
}

private fun awaitOne(port: Int) {
    val deadline = System.currentTimeMillis() + 10_000
    while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
        check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
        Thread.sleep(50)
    }
}

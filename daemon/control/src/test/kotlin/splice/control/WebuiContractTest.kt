// NEW: the webui contract gate (P4-WEBUI). The unmodified React dashboard consumes the daemon's
// /api/* JSON through the field names declared in webui/src/shared/api/index.ts. This test boots
// the ControlServer with a stub head and asserts every declared field is present in the daemon's
// actual JSON — so a rename in the Kotlin payload builders breaks THIS test, not the dashboard at
// runtime. Field sets are transcribed from index.ts @ pre-public-port-baseline (the comment is the source of
// truth; a drift shows up as a failing assertion here) — EXCEPT the economics bucket, whose set is
// DERIVED from EconomicsRow since V4-98 and asserted as a bijection; see ECONOMICS_WIRE_RENAMES for
// why that one is not a transcription. Manual click-through stays operator work; this pins the
// SHAPE contract automatically.
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

    /** V4-98: missing AND extra, both by name. [assertFields] is deliberately one-directional —
     *  most payloads here carry client-only or contract-nullable keys the Kotlin side never had to
     *  own — but a DERIVED denominator makes the other direction meaningful: a wire field with no
     *  property behind it is a field the dashboard reads and nothing in Kotlin maintains. */
    private fun assertBijection(obj: JsonObject, fields: List<String>, where: String) {
        val missing = fields.filter { it !in obj.keys }
        assertTrue(
            missing.isEmpty(),
            "$where: declared but NOT on the wire: $missing — a sum added to EconomicsRow reaches " +
                "the dashboard only if FileSources.kt's row copy AND EconomicsPayloads both carry " +
                "it (wire has ${obj.keys})",
        )
        val extra = obj.keys.filterNot { it in fields }
        assertTrue(
            extra.isEmpty(),
            "$where: on the wire but backed by NO EconomicsRow property: $extra — either add the " +
                "property or drop the field; the row type is the denominator (declared $fields)",
        )
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

    /** ClaudeHeadPayload (webui entities/claude-head/model/types.ts). ADDED BY V4-175, and it is
     *  the route that proves why this wall's route list has to be the denominator rather than a
     *  sample: /api/claude-head was absent from it, so the webui's types were written AHEAD of the
     *  daemon ("PENDING V4-129") and V4-129 then shipped a different payload. Nothing compared the
     *  two, and the settings page has been reading `head`, `config_dir` and `claude_on_path` off a
     *  response that never carried them — rendering two blanks and, worse, printing "nothing named
     *  claude" from an absent field while the daemon knew the answer under another name. */
    @Test
    fun `claude-head payload matches ClaudeHeadPayload`() = runBlocking {
        val payload = api("/api/claude-head")
        assertFields(payload, listOf("mode", "resolves_to", "shim_path", "real_binary_path"), "ClaudeHeadPayload")
        assertFields(
            payload["claude_logins"]!!.jsonObject,
            listOf("count", "selected", "labels", "constraint"),
            "ClaudeHeadPayload.claude_logins",
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

    /** EconomicsPayload + HeadEconomics + EconomicsBucket (webui shared/api). The bucket fields
     *  are the burn page's whole input; a rename here silently blanks the quota gauge, which is
     *  the one surface whose failure mode is reading SAFE while the plan drains.
     *
     *  V4-98: the bucket's expected field set is DERIVED from [EconomicsRow] (see
     *  [economicsWireNames]) instead of transcribed, and asserted as a BIJECTION against the wire.
     *  The hand list it replaced could not fail for a field absent from itself, which is the §24
     *  shape — EconomicsRow's sums reach the wire through THREE hand copies (EconomicsStore's
     *  EconomicsBucket -> FileSources.kt:56's row copy -> EconomicsPayloads' buildJsonObject) and
     *  every one of them keeps compiling when a sum is added with a default and forgotten. */
    @Test
    fun `economics payload matches EconomicsPayload plus nested bucket`() = runBlocking {
        val payload = api("/api/economics")
        assertFields(payload, listOf("retention_hours", "generated_at", HEADS_KEY), "EconomicsPayload")
        val head = payload[HEADS_KEY]!!.jsonArray.first().jsonObject
        assertFields(head, listOf("key", "label", "ceiling_tokens", "buckets"), "HeadEconomics")
        assertBijection(
            head["buckets"]!!.jsonArray.first().jsonObject,
            economicsWireNames(),
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

// ── V4-98: the economics bucket's field set, DERIVED from EconomicsRow ────────────────────────
//
// WHY. This file's other field lists are transcribed from webui/src/shared/api/index.ts and that
// is the right shape for them: they pin a CLIENT contract whose keys the Kotlin side does not own.
// The economics bucket is different — every one of its fields is one EconomicsRow sum, copied by
// hand three times (EconomicsStore.EconomicsBucket -> FileSources.kt:56 -> EconomicsPayloads'
// buildJsonObject), and none of those copies fails to compile when a sum is added with a default
// and forgotten at one hop. A hand list here checked one hand-authored list against another; it
// agreed with itself and could not fail for a field absent from both (§24). The denominator now
// comes from the type.
//
// WHY EconomicsRow AND NOT EconomicsBucket, which the row asked for. Two blocking premises, both
// recorded in the V4-98 ledger note:
//   1. splice.head.usage.EconomicsBucket is NOT @Serializable, and neither is EconomicsRow, and
//      EconomicsPayloads hand-builds the JSON with put(...) — so there is no
//      `serializer().descriptor.elementNames` anywhere on this path to read.
//   2. :daemon-control may not see :daemon-head (FileSources.kt:52 states that split as the reason the copy
//      exists at all), so this test — a :daemon-control test — cannot name EconomicsBucket even if it
//      were serializable.
// EconomicsRow is the nearest correct denominator: it is :daemon-control's own vocabulary, it is what
// EconomicsPayloads actually reads, and it is the type FileSources' copy targets, so a sum that
// reaches the row but not the wire fails here BY NAME.
//
// WHY JVM REFLECTION and not kotlin-reflect: :daemon-control declares no kotlin-reflect dependency, and
// adding one to ship a field list is a production dependency bought for a test. A data class's
// non-synthetic, non-static declared fields ARE its constructor properties; order is irrelevant
// because the assertion compares SETS.
//
// DISPOSITION for every property, because absence is not one. A property is accounted for as
// either mechanically named (camelCase -> snake_case, which is 11 of the 12) or explicitly
// RENAMED with a written reason below. A property in neither bucket cannot exist: the mapping is
// total by construction, and the bijection assertion then fails by name on whichever side drifted.
//
// NOT CAUGHT, and why. A field renamed in BOTH EconomicsRow and EconomicsPayloads at once still
// agrees here — the client contract is what would break, and that is what
// webui/src/shared/api/index.ts and the webui tests own. A value that is wrong rather than absent:
// the `economics ships input and cached separately` test below pins the three that must not be
// pre-netted. This wall owns the field SET.
private val ECONOMICS_WIRE_RENAMES = mapOf(
    // The wire says req_bytes for the client's own bytes and upstream_req_bytes for the bytes
    // splice forwarded; the property dropped the `req` because on the row side `upstreamBytes`
    // sits next to `reqBytes` and reads unambiguously. Mechanical snake_case would ask for
    // `upstream_bytes`, which the dashboard does not read.
    "upstreamBytes" to "upstream_req_bytes",
)

/** [EconomicsRow]'s properties as the wire spells them. The denominator, from the type. */
private fun economicsWireNames(): List<String> {
    val properties = EconomicsRow::class.java.declaredFields
        .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }
    // A reflection call that yields nothing would make the bijection below pass only against an
    // empty wire; the assertion is the guard, but say so where the list is built.
    check(properties.isNotEmpty()) {
        "EconomicsRow exposed no declared fields — the denominator is absent, so no field-set " +
            "assertion in this test can be trusted"
    }
    val unknownRenames = ECONOMICS_WIRE_RENAMES.keys - properties.toSet()
    check(unknownRenames.isEmpty()) {
        "ECONOMICS_WIRE_RENAMES names $unknownRenames, which EconomicsRow no longer declares — a " +
            "stale rename entry silently removes a field from the denominator"
    }
    return properties.map { ECONOMICS_WIRE_RENAMES[it] ?: snakeCase(it) }
}

private fun snakeCase(name: String): String =
    name.replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()

// OSS-M: fixed test ports lived in the Linux ephemeral range — transient outbound source ports
// collide at bind time on busy hosts; ports are OS-assigned. No readiness poll: ControlServer.start
// returns routed and bound (Ktor's default SEQUENTIAL startup runs the modules before
// NettyApplicationEngine's bind(...).sync(); V4-139).
private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

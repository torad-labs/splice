// Native console quota and legacy attribution are read from the same truth as Accounts.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.LoginStatus
import splice.app.auth.claude.ClaudeLoginLocation
import splice.app.auth.claude.ClaudeLoginRead
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.topology.API_KEY_WIRE
import splice.core.topology.AuthKindRegistry
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindow
import splice.core.usage.QuotaWindowView
import splice.core.util.WallClock
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.usage.CredentialQuotaFiles
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.usage.perf.PerfProjectionRead
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsProjection
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.ProjectedPerfRowsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "synthetic-native"
private const val TIMEOUT_MS = 30_000L

class NativeUsageTruthTest {
    @Test
    fun `native projected reads normalize attribution without full-window fallback or losing original references`() {
        val full = listOf("claude-code", "proved@example.invalid", null).mapIndexed { index, account ->
            PerfRow(
                ts = index.toLong() + 10,
                outcome = "ok",
                fields = mapOf("synthetic_metric" to 77L),
                account = account,
            )
        }
        val facts = full.map { it.copy(fields = emptyMap()) }
        val evidence = PerfRowsWindow(
            facts,
            oldestHeldTs = 1,
            dropsBefore = 3,
            readError = "synthetic read error",
            skipped = 7,
            newestHeldTs = 12,
        )
        var completed = 0
        val source = object : PerfRowsSource, ProjectedPerfRowsSource {
            override fun window(sinceMs: Long): PerfRowsWindow = error("a native projected read must not fall back")
            override fun <T> projected(sinceMs: Long, read: PerfProjectionRead<T>): T {
                val projection = object : PerfRowsProjection {
                    override val window: PerfRowsWindow = evidence
                    override fun complete(rows: List<PerfRow>): List<PerfRow> {
                        completed++
                        assertEquals(1, rows.size, "only the selected display row is materialized")
                        assertSame(
                            facts.first(),
                            rows.single(),
                            "normalization must restore the original projected reference",
                        )
                        return listOf(full.first())
                    }
                }
                return read(projection)
            }
        }
        val native = NativeAccountRows(source)
        native.projected(0) { projection ->
            val read = projection.window
            val normalized = facts.map { if (it.account == "claude-code") it.copy(account = null) else it }
            assertEquals(evidence.copy(rows = normalized), read)
            val row = projection.complete(listOf(read.rows.first())).single()
            assertNull(row.account)
            assertEquals(77L, row.fields["synthetic_metric"])
        }
        assertEquals(1, completed)
    }

    @TempDir
    lateinit var home: Path

    private val now = System.currentTimeMillis()
    private val reset = now / 1000L + 3600
    private val paths by lazy { StatePaths(baseOverride = home.resolve("state")) }
    private var probes = 0

    private fun location(id: ClaudeLoginPlaceId, account: String?): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve(id.wire))
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-${id.wire}"}}""",
        )
        val record = folder.resolve(".claude.json")
        val identity = account?.let { """"accountUuid":"$it","emailAddress":"proved@example.invalid"""" } ?: ""
        Files.writeString(record, """{"oauthAccount":{$identity}}""")
        return ClaudeLoginLocation(id, ClaudeLoginTarget(ClaudeHead(HEAD, folder), record), home)
    }

    private fun observed(id: ClaudeLoginPlaceId, pct: Double, at: Long = now) {
        val key = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-${id.wire}"))!!
        CredentialQuotaFiles(paths.quotaFile(HEAD), {}).observed(
            key,
            QuotaSnapshot(
                fiveHour = QuotaWindow(pct, reset, 18_000),
                sevenDay = QuotaWindow(65.0, reset + 86400, 604_800),
                plan = "max",
                updatedAt = at,
            ),
        )
    }

    private fun native(locations: List<ClaudeLoginLocation>): ClaudeLoginPlaces = object : ClaudeLoginPlaces {
        override fun places(): List<ClaudeLoginPlaceView> =
            ClaudeLoginRead(paths, {}, WallClock { now }).places(locations)
        override suspend fun refresh(place: ClaudeLoginPlaceId): ClaudeLoginPlaceView =
            places().single { it.id == place }
        override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus =
            error("no login in this fixture")
        override fun poll(id: String): LoginStatus? = null
        override suspend fun submit(id: String, code: String): Boolean = false
    }

    private fun managed(kind: String = "client"): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = HEAD
            override val label: String = "Synthetic"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, 0, "synthetic")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, kind, emptyMap())
        },
        usage = object : HeadUsageSource {
            override fun snapshot(): UsageView = UsageView(
                7,
                3,
                null,
                QuotaView(QuotaWindowView(99, reset, now / 1000), null, "wrong-head-plan"),
            )
            override suspend fun probeNow() { probes++ }
        },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        authKind = kind,
        perfRows = PerfRowsSource {
            PerfRowsWindow(
                listOf(null, "claude-code", "proved@example.invalid").mapIndexed { i, account ->
                    PerfRow(ts = now - i, outcome = "ok", fields = emptyMap(), account = account)
                },
                oldestHeldTs = now - 1000,
                dropsBefore = 3,
                newestHeldTs = now,
            )
        },
    )

    private fun serve(
        port: ClaudeLoginPlaces?,
        kind: String = "client",
        usageSource: HeadUsageSource? = null,
        family: String? = null,
        check: suspend (suspend (String, Boolean) -> JsonObject) -> Unit,
    ) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val key = MgmtKey(paths)
            val server = ControlServer(
                port = 0,
                heads = mapOf(
                    HEAD to managed(kind).let { head ->
                        if (usageSource == null) head else head.copy(usage = usageSource)
                    },
                ),
                config = ConfigService(paths),
                mgmtKey = key,
                dashboardHtml = { "<!doctype html>" },
                log = {},
            )
            // Late binding is intentional: UsageMount must not capture the construction-time null.
            server.ports.claudeLogins = port
            server.ports.declaredHeads = DeclaredHeads { mapOf(HEAD to DeclaredHead("synthetic-provider", null, family)) }
            HttpClient(CIO).use { client ->
                try {
                    server.start()
                    check { path, probe ->
                        val url = "http://127.0.0.1:${server.listeningPort}$path"
                        val response = if (probe) {
                            client.post(url) { header("Authorization", "Bearer ${key.get()}") }
                        } else {
                            client.get(url) { header("Authorization", "Bearer ${key.get()}") }
                        }
                        val text = response.bodyAsText()
                        assertEquals(200, response.status.value, text)
                        Json.parseToJsonElement(text).jsonObject
                    }
                } finally {
                    server.stop()
                }
            }
        }
    }

    private fun usage(root: JsonObject): JsonObject =
        root.getValue("heads").jsonArray.single().jsonObject.getValue("usage").jsonObject

    @Test
    fun `every registered subscription kind and the late-bound local family classify rows without plan metadata`() {
        val noPlan = HeadUsageSource { UsageView(0, 0, null) }
        val kinds = AuthKindRegistry.knownKinds().map { it.wire to "unpriced_plan_requests" } +
            (API_KEY_WIRE to "unpriced_local_requests")
        kinds.forEach { (kind, cause) ->
            serve(null, kind, noPlan, if (kind == API_KEY_WIRE) "local" else null) { read ->
                val block = read("/api/perf/turns?head=$HEAD&since=0", false)
                    .getValue("heads").jsonArray.single().jsonObject
                val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
                assertEquals("3", totals.getValue(cause).jsonPrimitive.content, kind)
                assertEquals("0", totals.getValue("unpriced_undeclared_requests").jsonPrimitive.content, kind)
            }
        }
        serve(null, API_KEY_WIRE, noPlan, "openai") { read ->
            val block = read("/api/perf/turns?head=$HEAD&since=0", false)
                .getValue("heads").jsonArray.single().jsonObject
            val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
            assertEquals("3", totals.getValue("unpriced_undeclared_requests").jsonPrimitive.content)
            assertEquals("0", totals.getValue("unpriced_local_requests").jsonPrimitive.content)
            assertEquals("0", totals.getValue("unpriced_plan_requests").jsonPrimitive.content)
        }
    }

    @Test
    fun `Accounts and Usage read the same sibling-observed account snapshot and refused probes retain it`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "one-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, "one-subscription"),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 12.0)
        serve(native(places)) { read ->
            val accounts = read("/api/accounts", false).getValue("accounts").jsonArray.map { it.jsonObject }
            val before = usage(read("/api/usage", false))
            val five = before.getValue("quota").jsonObject.getValue("five_hour").jsonObject
            accounts.forEach {
                assertEquals(
                    it.getValue("five_hour_used_percent").jsonPrimitive.content.toDouble(),
                    five.getValue("used_pct").jsonPrimitive.content.toDouble(),
                )
            }
            assertEquals(reset.toString(), five.getValue("resets_at").jsonPrimitive.content)
            assertEquals("7", before.getValue("output_tokens_5h").jsonPrimitive.content)
            assertEquals("3", before.getValue("entries").jsonPrimitive.content)
            assertEquals(
                before,
                usage(read("/api/usage/probe", true)),
                "a probe that files no new reading retains the snapshot",
            )
            assertEquals(1, probes)
        }
    }

    @Test
    fun `different and unknown identities never inherit another native place's quota`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "different-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, null),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 12.0)
        serve(native(places)) { read -> assertFalse(usage(read("/api/usage", false)).containsKey("quota")) }
    }

    @Test
    fun `a stale native snapshot remains visible without becoming a current warning`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "one-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, "one-subscription"),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 12.0, now - 3_600_000)
        serve(native(places)) { read ->
            val value = usage(read("/api/usage", false))
            val five = value.getValue("quota").jsonObject.getValue("five_hour").jsonObject
            assertEquals("12", five.getValue("used_pct").jsonPrimitive.content)
            assertEquals("false", five.getValue("current").jsonPrimitive.content)
            assertEquals("none", value.getValue("warn").jsonObject.getValue("source").jsonPrimitive.content)
            assertEquals((now / 1000L - 3600).toString(), five.getValue("observed_at").jsonPrimitive.content)
            assertEquals(value, usage(read("/api/usage/probe", true)))
            val accounts = read("/api/accounts", false).getValue("accounts").jsonArray
            accounts.forEach {
                assertEquals("12.0", it.jsonObject.getValue("five_hour_used_percent").jsonPrimitive.content)
                assertEquals("false", it.jsonObject.getValue("five_hour_current").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `legacy native place labels have no account identity and their Requests filter matches the aggregate`() {
        serve(null) { read ->
            val block = read("/api/perf/turns?head=$HEAD&since=0&local=0", false)
                .getValue("heads").jsonArray.single().jsonObject
            val groups = block.getValue("usage").jsonObject.getValue("accounts").jsonArray.map { it.jsonObject }
            assertEquals(2, groups.size)
            assertEquals("2", groups.single { it["key"] == JsonNull }.getValue("requests").jsonPrimitive.content)
            assertEquals("1", groups.single { it["key"] != JsonNull }.getValue("requests").jsonPrimitive.content)
            val filtered = read("/api/perf/turns?head=$HEAD&since=0&local=0&unattributed=account", false)
                .getValue("heads").jsonArray.single().jsonObject
            assertEquals("2", filtered.getValue("count").jsonPrimitive.content)
            assertTrue(filtered.getValue("rows").jsonArray.all { it.jsonObject["account"] == JsonNull })
        }
    }

    @Test
    fun `native attribution changes only the unproved name and preserves all window evidence`() {
        val row = PerfRow(ts = 100, outcome = "ok", fields = emptyMap(), account = "claude-code")
        val proved = row.copy(ts = 101, account = "proved@example.invalid")
        val held = PerfRowsWindow(listOf(row, proved), 50, 7, "synthetic unread generation", 2, 101)
        val source = PerfRowsSource { since ->
            assertEquals(42L, since)
            held
        }
        val normalized = NativeAccountRows(source).window(42)
        assertEquals(held.copy(rows = listOf(row.copy(account = null), proved)), normalized)
        assertNull(normalized.rows.first().account)
        assertSame(proved, normalized.rows.last(), "proved attribution remains borrowed, not rebuilt")
    }

    @Test
    fun `the same label on a nonnative provider stays an account label`() {
        serve(null, kind = "chatgpt-oauth") { read ->
            val block = read("/api/perf/turns?head=$HEAD&since=0&local=0", false)
                .getValue("heads").jsonArray.single().jsonObject
            assertEquals(3, block.getValue("usage").jsonObject.getValue("accounts").jsonArray.size)
            val five = usage(read("/api/usage", false)).getValue("quota").jsonObject.getValue("five_hour").jsonObject
            assertEquals("99", five.getValue("used_pct").jsonPrimitive.content)
        }
    }
}

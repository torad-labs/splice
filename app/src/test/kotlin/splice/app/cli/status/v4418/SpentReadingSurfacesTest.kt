// NEW: V4-418 — the three surfaces an operator reads (status, /health, usage) say what a head's CURRENT quota
// reading at 100% says, before any turn has been refused. Marlin (f7f1e9308): claudex read ready and Fleet OK while
// its own poll said the week was spent. V4-452 ruled the words: a full reading is not a refusal (claudex served 1,163
// turns on Oct 1 at a week read 100%), so status stays ready and says "week at 100%, resets <reset>", /health names
// it quotaFull apart from quotaResetAtEpochSeconds, and usage reports the plan window rather than a provider reset.
// Only a refusal the head holds reads "out of quota until". Driven through the production wiring: ManagedHeadFactory
// assembles the real head (its real primary QuotaTracker, its real HeadServer), a reading is recorded on that tracker
// the way the poller records one, and each surface is read from the head. The reset is rendered by V4-419's one zone
// rule, so the zone is pinned here instead of hoping the runner is in Chicago.
package splice.app.cli.status.v4418

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.cli.status.StatusTable
import splice.app.control.ManagedHead
import splice.app.control.UsageHeadAdapter
import splice.app.control.healthFor
import splice.app.control.readinessFor
import splice.app.head.HeadServerFactory
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.OnPrimaryQuota
import splice.app.head.StartQuotaPoller
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.daemonclient.DaemonProbe
import splice.head.usage.QuotaTracker
import splice.usage.quota.UsagePayloads
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

private const val KEY = "claudex"
private const val SIX_DAYS_S = 6L * 24 * 3_600
private const val SEVEN_DAY_S = 7L * 24 * 3_600
private const val MS = 1_000L

class SpentReadingSurfacesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: ManagedHead? = null

    @AfterEach
    fun stop() {
        running?.let { runBlocking { it.head.stop() } }
        scope.cancel()
    }

    private fun assemble(state: StatePaths, primary: (QuotaTracker) -> Unit): ManagedHead {
        val log = LogSink { }
        val config = ConfigService(state)
        val mgmtKey = MgmtKey(state)
        val signIn = SignInPlanner()
        val factory = ManagedHeadFactory(
            statePaths = state,
            providerAssembly = ProviderAssembly(
                state,
                scope,
                log,
                TokenUrlRefreshCall { _, _ -> error("refresh must not run during assembly") },
            ),
            headServerFactory = HeadServerFactory(config, mgmtKey, log),
            launchSpecFactory = LaunchSpecFactory(
                topology = Topology(),
                signInPlanner = signIn,
                mgmtKey = mgmtKey,
                buildInputs = HeadBuildInputs(config, signIn),
            ),
            probeScope = scope,
            log = log,
            startQuotaPoller = StartQuotaPoller { _, _, _, _ -> null },
            onPrimaryQuota = OnPrimaryQuota { primary(it) },
        )
        return factory.assembleHead(providerBuild(state), controlPort = 3098).also {
            running = it
            runBlocking { it.head.start() }
        }
    }

    private fun providerBuild(state: StatePaths): ProviderBuild {
        val model = ModelEntry(id = "gpt-5.6-sol", contextWindow = 400_000)
        return ProviderBuild(
            key = KEY,
            head = HeadConfig(
                provider = "codex",
                port = 0,
                discoveryPrefix = "claude-codex--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = "claudex", configDir = state.stateDir.toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = "chatgpt-oauth", file = state.stateDir.resolve("auth.json").toString()),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(model),
                defaultContextWindow = model.contextWindow,
            ),
            watchdog = WatchdogBudget(300.seconds, 300.seconds, 900.seconds),
            cfg = ConfigService(state, headOverrides = mapOf("quotaPoll" to "off")).getConfig(),
            loginCommand = "claudex login",
        )
    }

    private fun weekAt(used: Double): Pair<QuotaSnapshot, Long> {
        val now = System.currentTimeMillis()
        val resets = now / MS + SIX_DAYS_S
        val week = QuotaWindow(used, resets, SEVEN_DAY_S)
        return QuotaSnapshot(sevenDay = week, plan = "plus", updatedAt = now) to resets
    }

    private val topology = Topology(
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.invalid",
                auth = AuthConfig(kind = "api-key", env = "TEST_CODEX_KEY"),
            ),
        ),
        heads = mapOf(
            KEY to HeadConfig(
                provider = "codex", port = 3099, discoveryPrefix = "claude-codex--", pinnedModel = "m",
                claude = ClaudeWrapperConfig(command = "claudex"),
            ),
        ),
    )

    private val chicago = LocalTimeText(ZoneId.of("America/Chicago"))

    /** The status row the CLI prints from what the daemon's /health said. */
    private fun statusLine(tmp: Path, health: String): String {
        val bin = Files.createDirectory(tmp.resolve("bin"))
        val view = DaemonProbe.parseHealth(health)
        Files.createSymbolicLink(bin.resolve("claudex"), bin.resolve("target"))
        val env = EnvReader(mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_CODEX_KEY" to "synthetic-key")::get)
        val table = StatusTable(CliPalette(ColorDepth.NONE), WallClock(System::currentTimeMillis), chicago)
        return table.lines(
            topology,
            env,
            quotaResetAtEpochSeconds = view.quota.refusedUntil,
            quotaFull = view.quota.full,
        )[1]
    }

    private fun warn(head: ManagedHead, state: StatePaths): JsonObject {
        val payloads = UsagePayloads(
            UsageHeadAdapter.heads(mapOf(KEY to head)),
            ConfigService(state),
            WallClock(System::currentTimeMillis),
        )
        val row = Json.parseToJsonElement(payloads.usageJson()).jsonObject.getValue("heads").jsonArray.single()
        return row.jsonObject.getValue("usage").jsonObject.getValue("warn").jsonObject
    }

    @Test
    fun `a current week at 100 percent stays ready and says the week is full, with no turn refused`(
        @TempDir tmp: Path,
    ) {
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        lateinit var tracker: QuotaTracker
        val head = assemble(state) { tracker = it }
        val (spent, resets) = weekAt(100.0)
        tracker.record(spent)

        val health = healthFor(mapOf(KEY to head), readinessFor(mapOf(KEY to head), configuredHeads = 1)).json()
        val body = Json.parseToJsonElement(health).jsonObject
        assertNull(body["quotaResetAtEpochSeconds"], "a reading is not a refusal: $health")
        val full = body.getValue("quotaFull").jsonObject.getValue(KEY).jsonObject
        assertEquals("seven_day", full.getValue("window").jsonPrimitive.content, health)
        assertEquals(resets, full.getValue("resetsAtEpochSeconds").jsonPrimitive.content.toLong(), health)

        val line = statusLine(tmp, health)
        assertTrue(line.trimEnd().endsWith("ready · week at 100%, resets ${chicago.at(resets)}"), line)
        assertFalse(line.contains("out of quota"), line)

        val usage = warn(head, state)
        assertEquals("100", usage.getValue("pct").jsonPrimitive.content)
        assertNotEquals("provider_reset", usage.getValue("source").jsonPrimitive.content, usage.toString())
    }

    @Test
    fun `a week at 99 percent reads ready in all three, with nothing beside it`(@TempDir tmp: Path) {
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        lateinit var tracker: QuotaTracker
        val head = assemble(state) { tracker = it }
        tracker.record(weekAt(99.0).first)

        val health = healthFor(mapOf(KEY to head), readinessFor(mapOf(KEY to head), configuredHeads = 1)).json()
        assertNull(Json.parseToJsonElement(health).jsonObject["quotaResetAtEpochSeconds"], health)
        assertNull(Json.parseToJsonElement(health).jsonObject["quotaFull"], health)

        val line = statusLine(tmp, health)
        assertTrue(line.trimEnd().endsWith("ready"), line)
        assertNotEquals("provider_reset", warn(head, state).getValue("source").jsonPrimitive.content)
    }

    @Test
    fun `a refusal the head holds still reads out of quota, whatever the reading beside it`(@TempDir tmp: Path) {
        val refusedUntil = System.currentTimeMillis() / MS + SIX_DAYS_S
        val health = """{"version":"x","heads":1,"readyHeads":1,"failedHeads":0,
            "quotaResetAtEpochSeconds":{"$KEY":$refusedUntil},
            "quotaFull":{"$KEY":{"window":"seven_day","resetsAtEpochSeconds":$refusedUntil}}}"""

        val line = statusLine(tmp, health)
        assertTrue(line.trimEnd().endsWith("out of quota until ${chicago.at(refusedUntil)}"), line)
        assertFalse(line.contains("ready"), line)
    }
}

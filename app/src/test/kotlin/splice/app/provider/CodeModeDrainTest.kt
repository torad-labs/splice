// NEW: a real HeadServer restart ends a code-mode turn with its restart line before it closes the worker.
//
// Since Oct 29, 2026 a restart does not wait for a running turn (Marcos: "we already send a message saying the server
// is restarting and so the user knows about it, then we just have the new daemon take over"). The order still
// matters: the client reads the restart line, and only then is the code-mode child closed.
package splice.app.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.app.head.HeadServerFactory
import splice.app.head.HeadStores
import splice.app.head.HeadTelemetryStores
import splice.codemode.WorkerSpawn
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.TurnPrice
import splice.core.parse.AnthropicTurnBody
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ReasoningDisplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.compact.CompactStats
import splice.head.compaction.FileCompactionRecordings
import splice.head.perf.PerfStats
import splice.head.usage.EconomicsStore
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexProvider
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import splice.codemode.LegacyCodeModeRuntime as JvmCodeModeRuntime
import splice.codemode.LegacyHostLaunch as HostLaunch

/** The head's line for a restart, as the client reads it. */
private const val RESTART_SAID = "[splice] splice restarted during this turn."

@Timeout(45)
internal class CodeModeDrainTest {
    @Test
    fun `a restart tells a code-mode turn splice restarted, then closes its worker`(@TempDir tmp: Path) = runBlocking {
        val process = CompletableDeferred<Process>()
        val real = JvmCodeModeRuntime(
            launch = HostLaunch(
                classpath = checkNotNull(System.getProperty("codeMode.testClasspath")),
                spawn = WorkerSpawn { builder -> builder.start().also { process.complete(it) } },
            ),
        )
        val runtime = HeldRuntime(real)
        val mock = MockChatGptUpstream()
        val fixture = fixture(tmp, runtime, mock)
        val head = fixture.head
        val provider = fixture.provider
        try {
            head.start()
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${head.port}/v1/messages"))
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build()
            HttpClient.newHttpClient().use { client ->
                val answer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                withTimeout(10_000) { runtime.entered.await() }
                process.await()
                assertFalse(provider.stopped)
                // The worker is still held mid-turn: the restart must not wait for it.
                val stopping = async { head.stop() }
                val response = withTimeout(10_000) { answer.await() }
                withTimeout(10_000) { stopping.await() }
                assertTrue(response.body().contains(RESTART_SAID), response.body())
                assertFalse(response.body().contains("message_stop"), response.body())
                assertTrue(provider.stopped)
            }
        } finally {
            runtime.release.complete(Unit)
            head.stop()
            real.close()
            mock.stop()
        }
    }

    private data class Fixture(val head: HeadServer, val provider: DrainProvider, val token: String)

    private fun fixture(tmp: Path, runtime: HeldRuntime, mock: MockChatGptUpstream): Fixture {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(paths, envReader = { null })
        val mgmt = MgmtKey(paths)
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-6-astra", contextWindow = 1_000_000)),
            defaultContextWindow = 1_000_000,
        )
        val watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds)
        val ctx = ProviderBuild(
            key = "synthetic",
            head = HeadConfig(
                provider = "codex",
                port = 0,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "gpt-6-astra",
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = mock.baseUrl,
                auth = AuthConfig(kind = "api-key"),
            ),
            catalog = catalog,
            faultPlan = UpstreamFaultPlan(watchdog = watchdog, loginCommand = ""),
            cfg = config.getConfig("synthetic"),
        )
        val provider = provider(tmp, runtime, catalog, watchdog, mock)
        val head = HeadServerFactory(config, mgmt, {}).headServerFor(
            ctx,
            provider,
            stores(tmp, catalog),
            false,
            FileCompactionRecordings(tmp.resolve("recordings"), {}),
        )
        return Fixture(head, provider, TurnKey(mgmt).get())
    }

    private fun stores(tmp: Path, catalog: ModelCatalog) = HeadStores(
        usageStore = UsageStore(tmp.resolve("usage"), tmp.resolve("rate")),
        telemetry = HeadTelemetryStores(
            compactStats = CompactStats(tmp.resolve("compact")),
            perfStats = PerfStats(tmp.resolve("perf")),
            economics = EconomicsStore(tmp.resolve("economics"), TurnPrice(catalog)),
        ),
        quota = QuotaTracker(tmp.resolve("quota")),
        trace = null,
    )

    private fun provider(
        tmp: Path,
        runtime: HeldRuntime,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        mock: MockChatGptUpstream,
    ): DrainProvider {
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig({ runtime }, CodeModeStateLocation(tmp.resolve("cells"), tmp.resolve("old"))),
        )
        val tuning = ProviderTuning(
            ProviderName("synthetic", "synthetic"),
            catalog,
            "gpt-6-astra",
            FakeAuth(),
            ProviderLocations(mock.baseUrl),
            watchdog,
        )
        return DrainProvider(
            CodexProvider(tuning, ReasoningSettings(ReasoningDisplay.OFF, false, "high", "detailed")),
            bridge,
        )
    }

    private class DrainProvider(
        private val base: CodexProvider,
        private val bridge: CodexCodeModeBridge,
    ) : Provider by base {
        @Volatile var stopped = false
        override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
            val built = base.buildTurn(body, compact, sessionId)
            val turn = CodexCodeModeBridge.Turn(
                sessionId.orEmpty(),
                "synthetic",
                "gpt-6-astra",
                emptySet(),
                emptyList(),
            )
            return built.copy(
                roundInterceptor = RoundInterceptor { input, sink, post ->
                    var first = true
                    bridge.interceptor(turn, disableParallel = false).intercept(input, sink) { rewritten ->
                        if (first) {
                            first = false
                            RoundResult.Outcome(outer())
                        } else {
                            post(rewritten)
                        }
                    }
                },
            )
        }
        override fun onHeadStop() {
            stopped = true
            bridge.onHeadStop()
        }
        private fun outer(): TurnOutcome.Success {
            val source = "return 'drained';"
            val raw = buildJsonObject {
                put("type", "custom_tool_call")
                put("call_id", "outer")
                put("name", "exec")
                put("input", source)
            }
            val call = GatewayCustomCall("outer", "exec", source, raw)
            return TurnOutcome.Success(false, false, Usage(), handoffs = RoundHandoffs(customCalls = listOf(call)))
        }
    }

    private class HeldRuntime(private val real: JvmCodeModeRuntime) : CodeModeRuntime {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            val cell = real.start(source, tools, descriptions)
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                    entered.complete(Unit)
                    release.await()
                    return cell.advance(results)
                }
                override fun close() = cell.close()
            }
        }
        override fun close() = real.close()
    }

    private class FakeAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic", "synthetic")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    }
}

private const val REQUEST = """{"model":"gpt-6-astra","stream":true,""" +
    """"max_tokens":64,"messages":[{"role":"user","content":"start"}]}"""

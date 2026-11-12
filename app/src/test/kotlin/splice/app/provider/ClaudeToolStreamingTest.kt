package splice.app.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ClaudeToolStreamingTest {
    /** A Claude command with no account added: what every install has until someone adds one. */
    private fun accounts(tmp: Path): ClaudeAccountWiring =
        ClaudeAccountWiring(StatePaths(baseOverride = tmp.resolve("state")), LogSink { })

    @Test
    fun `client auth enables eager custom tool inputs without overriding caller choices`(
        @TempDir tmp: Path,
    ) = runTest {
        val arm = PassthroughArm(PassthroughAssembly(), accounts(tmp))
        val request = AnthropicParse.parseAnthropicBody(
            """{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}],"tools":[
                {"name":"plain","input_schema":{"type":"object"}},
                {"type":"custom","name":"typed","input_schema":{"type":"object"}},
                {"name":"buffered","input_schema":{"type":"object"},"eager_input_streaming":false},
                {"type":"web_search_20250305","name":"web_search"},
                {"name":"eager","input_schema":{"type":"object"},"eager_input_streaming":true}]}""",
        )
        for (kind in listOf(AuthKind.Client.wire, "api-key")) {
            val provider = arm.passthroughProvider(context(tmp, kind), "claude-test").provider
            val body = provider.buildTurn(request, compact = false, sessionId = null).requestBody
            val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
            val expectedDefault = if (kind == AuthKind.Client.wire) "true" else null
            assertEquals(expectedDefault, tools[0]["eager_input_streaming"]?.jsonPrimitive?.content, kind)
            assertEquals(expectedDefault, tools[1]["eager_input_streaming"]?.jsonPrimitive?.content, kind)
            assertEquals("false", tools[2]["eager_input_streaming"]?.jsonPrimitive?.content)
            assertNull(tools[3]["eager_input_streaming"])
            assertEquals("true", tools[4]["eager_input_streaming"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `nonstreaming fallback retains upstream tool input validation`(@TempDir tmp: Path) = runTest {
        val provider = PassthroughArm(PassthroughAssembly(), accounts(tmp))
            .passthroughProvider(context(tmp, AuthKind.Client.wire), "claude-test").provider
        val request = AnthropicParse.parseAnthropicBody(
            """{"model":"m","stream":false,"messages":[{"role":"user","content":"hi"}],"tools":[
                {"name":"plain","input_schema":{"type":"object"}},
                {"type":"custom","name":"typed","input_schema":{"type":"object"}},
                {"name":"eager","input_schema":{"type":"object"},"eager_input_streaming":true}]}""",
        )
        val body = provider.buildTurn(request, compact = false, sessionId = null).requestBody
        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        assertNull(tools[0]["eager_input_streaming"], "a nonstreaming fallback must not opt into eager inputs")
        assertNull(tools[1]["eager_input_streaming"], "typed custom tools must retain validation too")
        assertEquals("true", tools[2]["eager_input_streaming"]?.jsonPrimitive?.content)
    }

    private fun context(tmp: Path, kind: String): ProviderBuild {
        val key = "claude-test"
        val config = ConfigService(StatePaths(baseOverride = tmp.resolve("state")), envReader = { null })
        return ProviderBuild(
            key = key,
            head = HeadConfig(provider = "claude", port = 4100, discoveryPrefix = "claude-test--", pinnedModel = "m"),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://example.invalid",
                auth = AuthConfig(kind = kind),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-test--",
                models = listOf(ModelEntry(id = "m", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            faultPlan = UpstreamFaultPlan(
                watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
                loginCommand = "test login",
            ),
            cfg = config.getConfig(key),
        )
    }
}

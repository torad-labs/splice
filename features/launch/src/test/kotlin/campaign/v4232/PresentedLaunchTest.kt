// NEW: V4-232's launch of a head whose row the client resolves as a Claude model it knows (client_model). Claude
// Code 2.1.283 compacts against min(its window for the row, CLAUDE_CODE_AUTO_COMPACT_WINDOW) (bundle fn
// `CT`), and for a presented row that window is its own table's 200k, so the env must not cap below it:
// in real tokens the client's ceiling must be the runtime's own window, whatever side of 200k it is on.
// The head's rows reach its OWN settings.json only: a launch through the wrapped `claude` writes the
// operator's ~/.claude, and that file keeps exactly the overrides the operator wrote.
package campaign.v4232

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.wrap.WrapState
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.launch.ModelTiers
import splice.launch.recipe.LaunchService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

class PresentedLaunchTest(@param:TempDir private val tmp: Path) {
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun catalog(window: Long, clientModel: String? = "claude-sonnet-4-6") = ModelCatalog(
        discoveryPrefix = "claude-bonsai--",
        models = listOf(ModelEntry("bonsai-2-27b", "Bonsai", contextWindow = window, clientModel = clientModel)),
        defaultContextWindow = window,
        pinnedModel = "bonsai-2-27b",
    )

    private fun spec(cat: ModelCatalog) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-bonsai")),
        pinnedModel = cat.pinnedModel,
        availableModelIds = cat.availableModelIds(),
        modelLabels = mapOf("bonsai-2-27b" to "Bonsai"),
        discoveryPrefix = cat.discoveryPrefix,
        tiers = ModelTiers(modelOverrides = cat.presented.overrides),
        contextWindow = cat.clientLaunchWindow,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "true",
        loginCommand = "claude-bonsai login",
        signInLabel = "Bonsai",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3108,
        inferenceToken = "test-inference-token",
        apiTimeoutMs = 960_000,
    )

    private fun settings(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    /** The client's compaction ceiling for the pinned row, in the runtime's REAL tokens. */
    private fun realCeiling(cat: ModelCatalog): Double {
        val env = service.launch(spec(cat), emptyList(), dangerouslySkipPermissions = false).env
        val compact = env.getValue("CLAUDE_CODE_AUTO_COMPACT_WINDOW").toLong()
        return minOf(cat.clientContextWindowFor("bonsai-2-27b"), compact) / cat.usageScale("bonsai-2-27b")
    }

    @Test
    fun `a presented runtime under 200k compacts near its own ceiling, not at a sixth of it`() {
        assertEquals(32_768.0, realCeiling(catalog(32_768L)), 1e-6)
    }

    @Test
    fun `a presented runtime over 200k compacts at its own ceiling too`() {
        assertEquals(245_760.0, realCeiling(catalog(245_760L)), 1e-6)
    }

    @Test
    fun `the head's settings json maps the presented Claude model to the row`() {
        service.launch(spec(catalog(245_760L)), emptyList(), dangerouslySkipPermissions = false)

        assertEquals(
            buildJsonObject { put("claude-sonnet-4-6", "bonsai-2-27b") },
            settings(tmp.resolve(".claude-bonsai/settings.json"))["modelOverrides"],
        )
    }

    @Test
    fun `a presented row's repeated tier is its Claude model, not a wrapped id the client does not know`() {
        val env = service.launch(spec(catalog(245_760L)), emptyList(), dangerouslySkipPermissions = false).env
        val tiers = listOf("OPUS", "SONNET", "HAIKU", "FABLE").associateWith { env["ANTHROPIC_DEFAULT_${it}_MODEL"] }

        assertEquals(1, tiers.values.count { it == "bonsai-2-27b" }, "the first tier plants the row: $tiers")
        assertEquals(3, tiers.values.count { it == "claude-sonnet-4-6" }, "each repeat plants its Claude model: $tiers")
    }

    @Test
    fun `an unpresented row's repeated tiers keep the wrapped spelling`() {
        val env = service.launch(spec(catalog(245_760L, clientModel = null)), emptyList(), false).env
        val tiers = listOf("OPUS", "SONNET", "HAIKU", "FABLE").associateWith { env["ANTHROPIC_DEFAULT_${it}_MODEL"] }

        assertEquals(3, tiers.values.count { it == "claude-bonsai--bonsai-2-27b" }, "$tiers")
    }

    @Test
    fun `a head presenting nothing writes no overrides and plants the window it always did`() {
        val cat = catalog(32_768L, clientModel = null)
        val env = service.launch(spec(cat), emptyList(), dangerouslySkipPermissions = false).env

        assertEquals("32768", env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
        assertEquals("60000", env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"], "the 60k floor, as before V4-232")
        assertNull(settings(tmp.resolve(".claude-bonsai/settings.json"))["modelOverrides"])
    }

    @Test
    fun `a launch through the wrapped claude leaves the operator's own overrides exactly as written`() {
        val home = tmp.resolve("wrapped-home").createDirectories()
        val operators = buildJsonObject { put("claude-opus-4-6", "operator-profile-arn") }
        home.resolve(".claude").createDirectories()
        val own = buildJsonObject { put("modelOverrides", operators) }
        Files.writeString(home.resolve(".claude/settings.json"), own.toString())
        val stateStore = WrapStateStore(file = tmp.resolve("wrapped-state/claude-head-wrap.json"))
        stateStore.write(WrapState("/opt/claude/2.1.283", "/opt/claude/2.1.283", "/share/splice-launch", "", "", 0L))
        val materializer = ClaudeConfigMaterializer(home)
        val wrap = WrappedHead(home, stateStore = stateStore, materializer = materializer)
        val wrapping = LaunchService(materializer, wrap = wrap)
        val through = wrapping.wrap.launchThrough("claude") ?: error("a wrap state is present: claude must resolve")

        wrapping.launch(spec(catalog(245_760L)), emptyList(), dangerouslySkipPermissions = false, wrapped = through)

        assertEquals(operators, settings(home.resolve(".claude/settings.json"))["modelOverrides"])
    }
}

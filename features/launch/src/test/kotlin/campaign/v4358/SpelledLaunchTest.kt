// NEW: V4-358's launch of a head whose rows are handed to Claude Code with the 1M hint. Claude Code 2.1.283
// keeps 100 images in a request and 600 for an id matching /\[1m\]/i, so a screenshot session on an 872k
// row lost every image past the hundredth while the backend had room for them. The client's model id is a
// value the launch authors; what the row is called everywhere else does not change.
package campaign.v4358

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.launch.ModelTiers
import splice.launch.recipe.LaunchService
import java.nio.file.Files
import java.nio.file.Path

class SpelledLaunchTest(@param:TempDir private val tmp: Path) {
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private val sol = ModelEntry("gpt-6-sol", "Sol", contextWindow = 872_000L)
    private val mid = ModelEntry("gpt-5.6-sol", "Sol 5.6", contextWindow = 272_000L)

    private fun catalog(vararg rows: ModelEntry, pinned: String = rows.first().id) = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = rows.toList(),
        defaultContextWindow = 272_000L,
        pinnedModel = pinned,
    )

    private fun spec(cat: ModelCatalog, forwardClientAuth: Boolean = false) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-codex")),
        pinnedModel = cat.pinnedModel,
        availableModelIds = cat.availableModelIds(),
        modelLabels = cat.models.associate { it.id to it.label },
        tiers = ModelTiers(slots = mapOf("gpt-6-sol" to "opus", "gpt-5.6-sol" to "sonnet")),
        discoveryPrefix = cat.discoveryPrefix,
        contextWindow = cat.clientLaunchWindow,
        modelOptionsCache = buildJsonArray {
            cat.models.forEach {
                val option = mapOf("value" to JsonPrimitive(it.id), "context_window" to JsonPrimitive(it.contextWindow))
                add(JsonObject(option))
            }
        },
        statuslineCommand = "true",
        loginCommand = "claudex login",
        signInLabel = "Codex",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3101,
        inferenceToken = "test-inference-token",
        apiTimeoutMs = 960_000,
        forwardClientAuth = forwardClientAuth,
    )

    private fun launch(cat: ModelCatalog, forwardClientAuth: Boolean = false) =
        service.launch(spec(cat, forwardClientAuth).withWindows(cat), emptyList(), dangerouslySkipPermissions = false)

    private fun settings(): JsonObject =
        Json.parseToJsonElement(Files.readString(tmp.resolve(".claude-codex/settings.json"))).jsonObject

    private fun claudeJson(): JsonObject =
        Json.parseToJsonElement(Files.readString(tmp.resolve(".claude-codex/.claude.json"))).jsonObject

    /** The rule Claude Code applies to the model it holds: `Kd`, /\[1m\]/i (bundle 2.1.283). */
    private fun oneMillion(id: String?) = id != null && Regex("\\[1m]", RegexOption.IGNORE_CASE).containsMatchIn(id)

    @Test
    fun `an 872k head is launched on an id Claude Code counts as 1M-context`() {
        val env = launch(catalog(sol, mid)).env

        assertTrue(oneMillion(env["ANTHROPIC_MODEL"]), "the active model: ${env["ANTHROPIC_MODEL"]}")
        assertEquals("gpt-6-sol[1m]", env["ANTHROPIC_MODEL"])
        assertEquals("gpt-6-sol[1m]", env["ANTHROPIC_DEFAULT_OPUS_MODEL"], "a subagent on the tier keeps images too")
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_SONNET_MODEL"], "a 272k row is left as it was")
    }

    @Test
    fun `a head under the floor is launched exactly as before`() {
        val plain = catalog(mid, ModelEntry("gpt-5.4", "5.4", contextWindow = 272_000L))
        val env = launch(plain).env

        assertEquals("gpt-5.6-sol", env["ANTHROPIC_MODEL"])
        assertEquals("272000", env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
        assertEquals("272000", env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
        assertFalse(Files.readString(tmp.resolve(".claude-codex/settings.json")).contains("[1m]"))
    }

    @Test
    fun `the env window stays the pinned row's, and the auto-compact window rises to the client's 1M`() {
        val cat = catalog(sol, mid)
        val env = launch(cat).env

        assertEquals("872000", env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"], "an unspelled row still scales against this")
        assertEquals(CLAUDE_CODE_ONE_MILLION.toString(), env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
        val held = cat.clientContextWindowFor("gpt-6-sol[1m]")
        val compact = env.getValue("CLAUDE_CODE_AUTO_COMPACT_WINDOW").toLong()
        val real = minOf(held, compact) / cat.usageScale("gpt-6-sol[1m]")
        assertEquals(872_000.0, real, 1e-6, "the client's ceiling, in the backend's real tokens, is its window")
    }

    @Test
    fun `settings json and the picker cache carry the spelled ids, and the allowlist keeps the bare ones too`() {
        launch(catalog(sol, mid))

        assertEquals("gpt-6-sol[1m]", settings()["model"]?.jsonPrimitive?.content)
        val allowed = settings()["availableModels"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("gpt-6-sol[1m]" in allowed, "$allowed")
        assertTrue("gpt-6-sol" in allowed, "a session written before this still resumes on it: $allowed")
        assertTrue("gpt-5.6-sol" in allowed && "gpt-5.6-sol[1m]" !in allowed, "$allowed")
        val cache = claudeJson()["additionalModelOptionsCache"]!!.jsonArray
        val rows = cache.map { it.jsonObject["value"]!!.jsonPrimitive.content }
        assertEquals(listOf("gpt-6-sol[1m]", "gpt-5.6-sol"), rows, "one picker row per model, none twice")
    }

    @Test
    fun `a client-auth head keeps the ids the client owns`() {
        val native = catalog(
            ModelEntry("claude-opus-4-6", "Opus", contextWindow = 1_000_000L),
            ModelEntry("gpt-6-sol", "Sol", contextWindow = 872_000L),
        )
        val env = launch(native, forwardClientAuth = true).env

        assertEquals("claude-opus-4-6", env["ANTHROPIC_MODEL"])
        assertFalse(Files.readString(tmp.resolve(".claude-codex/settings.json")).contains("gpt-6-sol[1m]"))
    }

    @Test
    fun `a spec that was never given a catalog is launched as it always was`() {
        val cat = catalog(sol, mid)
        val bare = service.launch(spec(cat), emptyList(), dangerouslySkipPermissions = false).env

        assertEquals("gpt-6-sol", bare["ANTHROPIC_MODEL"])
        assertNull(bare["ANTHROPIC_DEFAULT_OPUS_MODEL"]?.takeIf { oneMillion(it) })
    }

    @Test
    fun `withWindows on a head that spells nothing returns an equal spec`() {
        val cat = catalog(mid)
        val boot = spec(cat)

        assertEquals(boot, boot.withWindows(cat))
    }
}

// NEW: 2026-09-22 — which models may stand behind Claude Code's tier slots once a head's catalog also
// carries the models its endpoint listed (ModelTiers.candidates). Its own class because
// LaunchServiceTest sits at detekt's LargeClass ceiling; the fixture is that file's, trimmed.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.ModelTiers
import splice.launch.launch
import java.nio.file.Path

class LaunchTierCandidatesTest(@param:TempDir private val tmp: Path) {

    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(available: List<String>, tiers: ModelTiers, pinned: String = available.first()) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-grok")),
        models = LaunchModels(
            pinnedModel = pinned,
            availableModelIds = available,
            modelLabels = available.associateWith { it },
            tiers = tiers,
            contextWindow = 500_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(
            loginCommand = "claude-grok login",
            signInLabel = "Grok (xAI)",
        ),
        gateway = LaunchGateway(
            statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
            port = 3104,
            inferenceToken = "test-inference-token",
            apiTimeoutMs = 960_000,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    private fun env(spec: LaunchSpec): Map<String, String> =
        service.launch(spec, emptyList(), dangerouslySkipPermissions = false).env

    // Without the candidates list the discovered "grok-code-mini" (listed second, and matching the
    // mini rule) would take both sonnet and haiku from the declared rows.
    @Test
    fun `discovered models are offered but never placed behind a tier`() {
        val env = env(
            spec(
                available = listOf("grok-4.5", "grok-code-mini", "grok-4.3"),
                tiers = ModelTiers(candidates = listOf("grok-4.5", "grok-4.3")),
            ),
        )
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"], "the discovered mini id is not a candidate")
        assertTrue(
            env.values.none { it == "grok-code-mini" },
            "a discovered id reaches the picker through the catalog, never a tier env",
        )
    }

    // 2026-09-23: the Codex provider declares no rows; its picker is the backend's list, newest family
    // first, exactly as the endpoint orders it. The tier NAMES still place the pinned model's own family.
    @Test
    fun `a provider with no rows takes its tiers from the backend's names, in the pinned model's family`() {
        val backend = listOf(
            "gpt-6-astra",
            "gpt-6-sol",
            "gpt-6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
        )
        val env = env(spec(available = backend, tiers = ModelTiers(candidates = emptyList()), pinned = "gpt-5.6-sol"))
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("gpt-5.6-terra", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("gpt-5.6-luna", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"], "not the newer family's gpt-6-luna")
    }

    // The case tierModelIds() was written against: an OpenRouter head lists hundreds of ids, some of them
    // ending in a Codex tier name. Outside the pinned model's family a name places nothing.
    @Test
    fun `a discovered tier name outside the pinned model's family takes no tier`() {
        val pinned = "anthropic/claude-haiku-4.5"
        val env = env(
            spec(
                available = listOf(pinned, "openai/gpt-6-sol", "openai/gpt-6-luna", "qwen/qwen3-coder-mini"),
                tiers = ModelTiers(candidates = listOf(pinned)),
            ),
        )
        listOf("OPUS", "SONNET", "HAIKU").forEach { slot ->
            assertEquals(pinned, env["ANTHROPIC_DEFAULT_${slot}_MODEL"], slot)
        }
    }

    @Test
    fun `an explicitly mapped discovered model can carry a tier without positional candidacy`() {
        val env = env(
            spec(
                available = listOf("fixture/pinned", "fixture/discovered"),
                tiers = ModelTiers(
                    byTier = mapOf("opus" to "fixture/discovered"),
                    candidates = listOf("fixture/pinned"),
                ),
            ),
        )
        assertEquals("fixture/discovered", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertTrue("ANTHROPIC_DEFAULT_SONNET_MODEL" !in env)
    }

    @Test
    fun `an explicit tier missing from the roster stays unmapped instead of using positional fallback`() {
        val recipe = service.launch(
            spec(
                available = listOf("fixture/pinned"),
                tiers = ModelTiers(byTier = mapOf("opus" to "fixture/absent")),
            ),
            emptyList(),
            dangerouslySkipPermissions = false,
        )
        assertTrue("ANTHROPIC_DEFAULT_OPUS_MODEL" !in recipe.env, recipe.env.toString())
        assertTrue("ANTHROPIC_DEFAULT_OPUS_MODEL" in recipe.unset, recipe.unset.toString())
    }

    // Two tiers may name one model (Marcos, 2026-10-15: claudex runs opus and sonnet on gpt-6.1-sol).
    // Both tiers are planted; the repeat carries the head's discovery spelling of the same model.
    @Test
    fun `two tiers declared on one model are both planted`() {
        val base = spec(
            available = listOf("gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra"),
            tiers = ModelTiers(
                byTier = mapOf(
                    "opus" to "gpt-6.1-sol",
                    "sonnet" to "gpt-6.1-sol",
                    "haiku" to "gpt-6-luna",
                    "fable" to "gpt-6-astra",
                ),
            ),
        )
        val env = env(base.copy(models = base.models.copy(discoveryPrefix = "claudex--")))
        assertEquals("gpt-6.1-sol", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("claudex--gpt-6.1-sol", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("gpt-6-luna", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("gpt-6-astra", env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
    }

    // No candidates list is every offered id — the positional scheme every head had before discovery.
    @Test
    fun `without candidates every offered id is placed as before`() {
        val env = env(spec(available = listOf("grok-4.5", "grok-code-mini", "grok-4.3"), tiers = ModelTiers()))
        assertEquals("grok-code-mini", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
    }
}

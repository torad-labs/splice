// NEW: 2026-09-22 — which models may stand behind Claude Code's tier slots once a head's catalog also
// carries the models its endpoint listed (ModelTiers.candidates). Its own class because
// LaunchServiceTest sits at detekt's LargeClass ceiling; the fixture is that file's, trimmed.
package splice.launch.recipe

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.launch.ModelTiers
import java.nio.file.Files

class LaunchTierCandidatesTest {

    private val tmp = Files.createTempDirectory("launch-tier-candidates-test")
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(available: List<String>, tiers: ModelTiers) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-grok")),
        pinnedModel = available.first(),
        availableModelIds = available,
        modelLabels = available.associateWith { it },
        tiers = tiers,
        contextWindow = 500_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claude-grok login",
        signInLabel = "Grok (xAI)",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3104,
        inferenceToken = "test-inference-token",
        apiTimeoutMs = 960_000,
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

    // No candidates list is every offered id — the positional scheme every head had before discovery.
    @Test
    fun `without candidates every offered id is placed as before`() {
        val env = env(spec(available = listOf("grok-4.5", "grok-code-mini", "grok-4.3"), tiers = ModelTiers()))
        assertEquals("grok-code-mini", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
    }
}

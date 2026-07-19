// NEW: unit-level proof for the safe-by-default launch recipe (OSS-B). LaunchService.launch must
// never add --dangerously-skip-permissions unless the caller explicitly opts in via
// dangerouslySkipPermissions=true — and doing so must surface a non-null warning, never silently.
// LaunchSpec construction mirrors ControlServerTest.kt/WebuiContractTest.kt in this same package.
package splice.control

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import java.nio.file.Files

class LaunchServiceTest {

    private val tmp = Files.createTempDirectory("launch-service-test")
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(head: String) = LaunchSpec(
        configDir = tmp.resolve(".claude-$head"),
        pinnedModel = "gpt-5.6-sol",
        availableModelIds = listOf("gpt-5.6-sol", "gpt-5.4-mini"),
        modelLabels = mapOf("gpt-5.6-sol" to "Codex 5.6 Sol", "gpt-5.4-mini" to "Codex 5.4 Mini"),
        contextWindow = 272000,
        modelOptionsCache = kotlinx.serialization.json.buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claudex login",
        signInLabel = "Codex (ChatGPT)",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3099,
    )

    @Test
    fun `default recipe is safe - no skip-permissions flag, no warning`() {
        val recipe = service.launch(spec("codex"), extraArgs = listOf("-c"), dangerouslySkipPermissions = false)
        assertFalse(recipe.argv.contains("--dangerously-skip-permissions"))
        assertTrue(recipe.argv.contains("-c"))
        assertNull(recipe.warning)
    }

    @Test
    fun `opt-in engages the flag and surfaces a warning`() {
        val recipe = service.launch(spec("grok"), extraArgs = emptyList(), dangerouslySkipPermissions = true)
        assertTrue(recipe.argv.contains("--dangerously-skip-permissions"))
        assertNotNull(recipe.warning)
    }
}

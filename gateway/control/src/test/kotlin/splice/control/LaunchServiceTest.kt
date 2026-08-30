// NEW: unit-level proof for the safe-by-default launch recipe (OSS-B). LaunchService.launch must
// never add --dangerously-skip-permissions unless the caller explicitly opts in via
// dangerouslySkipPermissions=true — and doing so must surface a non-null warning, never silently.
// LaunchSpec construction mirrors ControlServerTest.kt/WebuiContractTest.kt in this same package.
package splice.control

import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun spec(
        head: String,
        pinned: String = "gpt-5.6-sol",
        available: List<String> = listOf("gpt-5.6-sol", "gpt-5.4-mini"),
        labels: Map<String, String> = available.associateWith { it },
    ) = LaunchSpec(
        configDir = tmp.resolve(".claude-$head"),
        pinnedModel = pinned,
        availableModelIds = available,
        modelLabels = labels,
        contextWindow = 272000,
        modelOptionsCache = kotlinx.serialization.json.buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claudex login",
        signInLabel = "Codex (ChatGPT)",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3099,
        inferenceToken = "test-inference-token",
    )

    @Test
    fun `default recipe is safe - no skip-permissions flag, no warning`() {
        val recipe = service.launch(spec("codex"), extraArgs = listOf("-c"), dangerouslySkipPermissions = false)
        assertFalse(recipe.argv.contains("--dangerously-skip-permissions"))
        assertTrue(recipe.argv.contains("-c"))
        assertNull(recipe.warning)
        assertTrue(recipe.env["ANTHROPIC_AUTH_TOKEN"] == "test-inference-token")
    }

    @Test
    fun `opt-in engages the flag and surfaces a warning`() {
        val recipe = service.launch(spec("grok"), extraArgs = emptyList(), dangerouslySkipPermissions = true)
        assertTrue(recipe.argv.contains("--dangerously-skip-permissions"))
        assertNotNull(recipe.warning)
    }

    @Test
    fun `launch preserves ambient no-proxy entries and adds loopback`() {
        val merged = LaunchService(
            ClaudeConfigMaterializer(tmp),
            envReader = { name -> if (name == "NO_PROXY") "corp.internal,localhost" else null },
        ).launch(spec("codex"), emptyList(), dangerouslySkipPermissions = false)
            .env
            .getValue("NO_PROXY")
        assertTrue(merged.contains("corp.internal"))
        assertTrue(merged.contains("127.0.0.1"))
        assertTrue(merged.split(',').count { it == "localhost" } == 1)
    }

    @Test
    fun `codex 5_6 tiers map haiku-luna sonnet-terra opus-and-fable-sol`() {
        // Live catalog order puts mini AFTER the 5.6 tiers; the old heuristic parked haiku on
        // mini and fable on luna. Name-aware slots must pin the 5.6 ladder regardless of order.
        val available = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.3-codex-spark",
        )
        val env = service.launch(
            spec("codex", pinned = "gpt-5.6-sol", available = available),
            emptyList(),
            dangerouslySkipPermissions = false,
        ).env
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("gpt-5.6-terra", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("gpt-5.6-luna", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
    }

    @Test
    fun `catalogs without sol-terra-luna keep positional mini fallback`() {
        val available = listOf("grok-4.5", "grok-4.3", "grok-build-latest")
        val env = service.launch(
            spec("grok", pinned = "grok-4.5", available = available),
            emptyList(),
            dangerouslySkipPermissions = false,
        ).env
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"]) // no mini → at(1)
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_FABLE_MODEL"]) // shares frontier
    }

    // ── native-auth heads (campaign claude-head, CH-8) ────────────────────────────────────────
    //
    // Every OTHER head serves a foreign vendor, so the recipe replaces the client's Anthropic
    // session with the gateway bearer and nails /login shut. A claude head's upstream IS Anthropic
    // and it forwards the client's own credential, so all three of those moves are exactly wrong:
    // stripping removes what gets forwarded, planting the bearer overrides it, and disabling /login
    // shuts the only door that can heal a rejected credential.

    private fun nativeSpec() = spec("claude-splice").copy(forwardClientAuth = true)

    @Test
    fun `a native-auth head keeps the client's own credentials`() {
        val recipe = service.launch(nativeSpec(), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        assertTrue(recipe.unset.isEmpty(), "nothing may be stripped: ${recipe.unset}")
        assertNull(recipe.env["ANTHROPIC_AUTH_TOKEN"], "the gateway bearer would override the client's own")
    }

    @Test
    fun `a native-auth head keeps login and logout available`() {
        val env = service.launch(nativeSpec(), extraArgs = emptyList(), dangerouslySkipPermissions = false).env
        assertNull(env["DISABLE_LOGIN_COMMAND"])
        assertNull(env["DISABLE_LOGOUT_COMMAND"])
    }

    @Test
    fun `a native-auth head still gets the proxy and model surface`() {
        val env = service.launch(nativeSpec(), extraArgs = emptyList(), dangerouslySkipPermissions = false).env
        assertEquals("http://127.0.0.1:3099", env["ANTHROPIC_BASE_URL"])
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_MODEL"])
        assertEquals("1", env["SPLICE"])
    }

    // The regression that matters most: foreign heads must be BYTE-IDENTICAL to before this
    // feature existed. A default-valued flag is easy to leak into the wrong branch.
    @Test
    fun `a foreign-vendor head is unchanged - bearer planted, session stripped, login disabled`() {
        val recipe = service.launch(spec("codex"), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        assertEquals("test-inference-token", recipe.env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("1", recipe.env["DISABLE_LOGIN_COMMAND"])
        assertEquals("1", recipe.env["DISABLE_LOGOUT_COMMAND"])
        assertEquals(
            listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_OAUTH_REFRESH_TOKEN"),
            recipe.unset,
        )
    }
}

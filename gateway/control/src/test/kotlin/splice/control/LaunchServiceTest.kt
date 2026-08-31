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

    // Declared slots (2026-08-30). The positional heuristic maps four Claude tier slots onto
    // whatever the catalog order happens to be, which on a grok-shaped roster lands TWO models in
    // four slots — grok-4.6 as both OPUS and FABLE, grok-build-latest as both SONNET and HAIKU —
    // so the picker shows the same names repeatedly. A head that declares `slot` per row gets
    // EXACTLY its declared tiers: positional fill for the undeclared ones just re-created the
    // duplication on any roster smaller than four (codex redo verdict — a 2-model head still
    // planted one model in 3 slots), so declaring anything retires positional order outright.
    @Test
    fun `declared slots map each tier to its own model, whatever the catalog order`() {
        val available = listOf("grok-4.6", "grok-build-latest", "grok-4.3", "grok-build-0.1")
        val env = service.launch(
            spec("grok", pinned = "grok-4.6", available = available).copy(
                modelSlots = mapOf(
                    "grok-4.6" to "opus",
                    "grok-4.3" to "sonnet",
                    "grok-build-0.1" to "haiku",
                    "grok-build-latest" to "fable",
                ),
            ),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        ).env

        assertEquals("grok-4.6", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("grok-build-0.1", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("grok-build-latest", env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
        val assigned = listOf("OPUS", "SONNET", "HAIKU", "FABLE").map { env["ANTHROPIC_DEFAULT_${it}_MODEL"] }
        assertEquals(assigned.size, assigned.toSet().size, "no model may answer two tiers: $assigned")
    }

    // The exact live target: the grok head serves two models and declares two slots. Positional
    // fill for HAIKU/FABLE handed both back to grok-4.6 (3 tiers, one model) — the very screen
    // this feature exists to clean up. Undeclared tiers must not exist in the recipe at all.
    @Test
    fun `a two-model roster declaring opus and sonnet emits no haiku or fable slot`() {
        val recipe = service.launch(
            spec("grok", pinned = "grok-4.6", available = listOf("grok-4.6", "grok-4.5"))
                .copy(modelSlots = mapOf("grok-4.6" to "opus", "grok-4.5" to "sonnet")),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        )
        val env = recipe.env

        assertEquals("grok-4.6", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        // Absent from env is not enough: splice-launch execs `env` WITHOUT -i, so a nested launch
        // inherits the OUTER head's triplets — every un-emitted tier must be actively SCRUBBED or
        // the tier points at a model this head cannot serve (codex redo verdict, 2026-08-30).
        for (tier in listOf("HAIKU", "FABLE")) {
            for (suffix in listOf("MODEL", "MODEL_NAME", "MODEL_DESCRIPTION")) {
                val name = "ANTHROPIC_DEFAULT_${tier}_$suffix"
                assertNull(env[name], "$tier must stay un-set, not duplicated")
                assertTrue(name in recipe.unset, "$name must be scrubbed from the inherited env")
            }
        }
        for (tier in listOf("OPUS", "SONNET")) {
            assertFalse("ANTHROPIC_DEFAULT_${tier}_MODEL" in recipe.unset, "emitted tiers are not scrubbed")
        }
    }

    @Test
    fun `a partial declaration emits only its declared tier - positional order is retired`() {
        val available = listOf("grok-4.6", "grok-build-latest", "grok-4.3")
        val env = service.launch(
            spec("grok", pinned = "grok-4.6", available = available)
                .copy(modelSlots = mapOf("grok-4.3" to "haiku")),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        ).env

        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"], "the declared slot wins")
        assertNull(env["ANTHROPIC_DEFAULT_OPUS_MODEL"], "an undeclared tier is omitted, never filled")
        assertNull(env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertNull(env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
    }

    // Discovery retirement (2026-08-30). CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY made the
    // picker query /v1/models, which serves the same roster AGAIN under wrapped provider-prefixed
    // ids — every model listed twice, and Claude Code cannot dedupe the two spellings. Worse, a
    // wrapped ACTIVE id makes it ignore CLAUDE_CODE_MAX_CONTEXT_TOKENS (ab5ca6b: honored for
    // unwrapped names only), which per-head context windows depend on. The materialized bare-id
    // roster is the picker's one source; this arm was red before the env stopped being set.
    @Test
    fun `gateway model discovery stays off - the materialized roster is the picker's one source`() {
        val recipe = service.launch(spec("codex"), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        assertNull(
            recipe.env["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"],
            "discovery would re-add every model under a wrapped /v1/models id",
        )
        // Not setting it is not enough — an ambient =1 in the launching shell survives `env`
        // without -i, so the recipe must scrub it (codex redo verdict, 2026-08-30).
        assertTrue(
            "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY" in recipe.unset,
            "an inherited =1 would re-enable discovery: ${recipe.unset}",
        )
        // The roster still reaches the picker: the materializer wrote the full selected catalog.
        val cfg = tmp.resolve(".claude-codex")
        val settings = Files.readString(cfg.resolve("settings.json"))
        assertTrue(settings.contains("gpt-5.6-sol") && settings.contains("gpt-5.4-mini"))
        assertTrue(Files.readString(cfg.resolve(".claude.json")).contains("additionalModelOptionsCache"))
    }

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
        // No CREDENTIAL may be stripped — those variables are what this head forwards. Non-credential
        // hygiene (the discovery scrub) is allowed and wanted: this head's picker reads the same
        // materialized roster as every other.
        for (credential in listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_OAUTH_REFRESH_TOKEN")) {
            assertFalse(credential in recipe.unset, "a native head must not strip $credential: ${recipe.unset}")
        }
        assertTrue("CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY" in recipe.unset)
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

    // The regression that matters most: foreign heads' ENV must be BYTE-IDENTICAL to before this
    // feature existed. A default-valued flag is easy to leak into the wrong branch. The unset list
    // is allowed exactly one addition since: the discovery scrub (all four tiers emit on a
    // declare-nothing head, so no tier scrubs appear here).
    @Test
    fun `a foreign-vendor head is unchanged - bearer planted, session stripped, login disabled`() {
        val recipe = service.launch(spec("codex"), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        assertEquals("test-inference-token", recipe.env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("1", recipe.env["DISABLE_LOGIN_COMMAND"])
        assertEquals("1", recipe.env["DISABLE_LOGOUT_COMMAND"])
        assertEquals(
            listOf(
                "ANTHROPIC_API_KEY",
                "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
                "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY",
            ),
            recipe.unset,
        )
    }
}

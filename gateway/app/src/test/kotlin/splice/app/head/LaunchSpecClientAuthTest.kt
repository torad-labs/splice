// NEW (post-review, PR 99 finding 6): the two client-auth consumers must agree. ManagedHeadFactory
// derives forwardClientAuth structurally from the resolved ClientAuthProvider; LaunchSpecFactory
// consumes that same resolved flag to control ANTHROPIC_AUTH_TOKEN, ambient credential stripping,
// and /login. ProviderAssembly now rejects registered client auth on non-passthrough dialects before
// this factory is reached. The synthetic lower-level test remains to ensure LaunchSpecFactory never
// re-derives the flag from a raw auth.kind string if called directly.
package splice.app.head

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.SignInPlanner
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderBuild
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.HeadModel
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class LaunchSpecClientAuthTest {

    @Test
    fun `CLIENT_AUTH_KIND stays equal to AuthKind Client wire`() {
        assertEquals(AuthKind.Client.wire, CLIENT_AUTH_KIND)
    }

    private fun factory(tmp: Path): LaunchSpecFactory {
        val statePaths = StatePaths(baseOverride = tmp)
        val signInPlanner = SignInPlanner()
        return LaunchSpecFactory(
            topology = Topology(),
            signInPlanner = signInPlanner,
            mgmtKey = MgmtKey(statePaths),
            buildInputs = HeadBuildInputs(ConfigService(statePaths), signInPlanner),
        )
    }

    private fun build(tmp: Path, dialect: Dialect): ProviderBuild = ProviderBuild(
        key = "claude-splice",
        head = HeadConfig(
            provider = "anthropic",
            port = 3100,
            discoveryPrefix = "claude-splice--",
            pinnedModel = "m",
            claude = ClaudeWrapperConfig(command = "claude-splice", configDir = "$tmp/cfg"),
        ),
        // Synthetic divergent shape: ProviderAssembly rejects this registered tuple at runtime,
        // but direct factory tests can still construct it to pin resolved-flag ownership.
        providerCfg = ProviderConfig(
            dialect = dialect,
            baseUrl = "https://example.invalid",
            auth = AuthConfig(kind = CLIENT_AUTH_KIND),
        ),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-splice--",
            models = listOf(ModelEntry(id = "m", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        ),
        watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
        cfg = ConfigService(StatePaths(baseOverride = tmp)).getConfig(),
        loginCommand = "claude-splice login",
    )

    /** Lower-level ownership guard. Runtime assembly rejects this registered incompatible tuple,
     *  but the factory must still consume the resolved flag rather than reinterpret auth.kind. */
    @Test
    fun `a synthetic incompatible context cannot rederive native client auth`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.OPENAI_RESPONSES),
            controlPort = 3099,
            forwardClientAuth = false, // synthetic resolved input; runtime assembly rejects the tuple
        )
        assertFalse(
            spec.forwardClientAuth,
            "the recipe must follow its resolved input, not rederive from the declared auth.kind",
        )
    }

    /** Positive factory half: a resolved true input must be preserved rather than hardcoded false.
     *  AuthDialectCompatibilityBootTest covers the real passthrough arm derivation. */
    @Test
    fun `a resolved client-auth flag is preserved`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.ANTHROPIC_PASSTHROUGH),
            controlPort = 3099,
            forwardClientAuth = true,
        )
        assertTrue(
            spec.forwardClientAuth,
            "the anthropic-passthrough client arm forwards the caller's own credential — " +
                "its launch must keep that credential and keep /login open",
        )
    }

    @Test
    fun `model picker cache uses the effective per-head window`(@TempDir tmp: Path) {
        val declared = ModelEntry(id = "m", label = "Model", contextWindow = 256_000)
        val effective = declared.copy(contextWindow = 333_000)
        val base = build(tmp, Dialect.ANTHROPIC_PASSTHROUGH)
        val ctx = base.copy(
            providerCfg = base.providerCfg.copy(models = listOf(declared)),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-splice--",
                models = listOf(effective),
                defaultContextWindow = effective.contextWindow,
            ),
        )

        val spec = factory(tmp).launchSpecFor(ctx, 3099, forwardClientAuth = true)
        val cachedWindow = spec.modelOptionsCache.jsonArray.single().jsonObject
            .getValue("context_window").jsonPrimitive.long

        assertEquals(333_000, cachedWindow)
    }

    @Test
    fun `launch spec preserves a context window above Int max`(@TempDir tmp: Path) {
        val window = Int.MAX_VALUE.toLong() + 1
        val model = ModelEntry(id = "m", contextWindow = window)
        val base = build(tmp, Dialect.ANTHROPIC_PASSTHROUGH)
        val ctx = base.copy(
            providerCfg = base.providerCfg.copy(models = listOf(model)),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-splice--",
                models = listOf(model),
                defaultContextWindow = window,
                pinnedModel = model.id,
            ),
        )

        val spec = factory(tmp).launchSpecFor(ctx, 3099, forwardClientAuth = true)

        assertEquals(window, spec.contextWindow)
    }

    @Test
    fun `launch spec exposes labels only for the head catalog`(@TempDir tmp: Path) {
        val shown = ModelEntry(id = "shown", label = "Shown", contextWindow = 500_000)
        val hidden = ModelEntry(id = "hidden", label = "Hidden", contextWindow = 256_000)
        val base = build(tmp, Dialect.ANTHROPIC_PASSTHROUGH)
        val ctx = base.copy(
            head = base.head.copy(
                pinnedModel = shown.id,
                models = listOf(HeadModel(shown.id, slot = "opus")),
            ),
            providerCfg = base.providerCfg.copy(models = listOf(hidden, shown)),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-splice--",
                models = listOf(shown),
                defaultContextWindow = shown.contextWindow,
                pinnedModel = shown.id,
            ),
        )

        val spec = factory(tmp).launchSpecFor(ctx, 3099, forwardClientAuth = true)

        assertEquals(listOf(shown.id), spec.availableModelIds)
        assertEquals(mapOf(shown.id to shown.label), spec.modelLabels)
        assertEquals(mapOf(shown.id to "opus"), spec.modelSlots)
        assertEquals(
            listOf(shown.id),
            spec.modelOptionsCache.jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.content },
        )
    }
}

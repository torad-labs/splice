// NEW (post-review, PR 99 finding 6): the two client-auth facts must agree.
//
// A head has ONE client-auth fact with TWO consumers. ManagedHeadFactory derives forwardClientAuth
// STRUCTURALLY (`wired.auth is ClientAuthProvider`) and it decides whether the gateway door opens
// without a mgmt key. LaunchSpecFactory's forwardClientAuth decides whether ANTHROPIC_AUTH_TOKEN is
// planted into the launched Claude Code process, whether the operator's ambient Anthropic
// credentials are stripped from it, and whether /login stays enabled.
//
// Those two were derived independently: the LaunchSpec leg read the DECLARED TOML string
// (`auth.kind == "client"`) while its sibling read the RESOLVED credential. kind and dialect are
// independent TOML fields and ClientAuthProvider has exactly one construction site — the
// anthropic-passthrough arm (PassthroughArm.kt) — so `kind = "client"` on any other dialect makes
// them disagree: the door correctly stays SHUT while the launch recipe still behaved as though the
// client were authenticating itself. Nothing caught that, which is why this file exists: the
// existing client-auth tests pin the door (HeadServerClientAuthTest), the sign-in plan
// (SignInPlanMatrixTest) and the recipe built FROM a LaunchSpec (LaunchServiceTest), but none of
// them pinned the DERIVATION that produces the flag.
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
        // The divergent shape: `kind = "client"` is declared regardless of dialect, because they
        // are independent TOML fields — nothing in the schema stops an operator writing this.
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

    /**
     * THE REGRESSION. `kind = "client"` on a NON-passthrough dialect resolves to no
     * ClientAuthProvider, so the gateway keeps enforcing the mgmt key — and the launch recipe must
     * agree. Deriving this from the declared string instead returned true here, which plants no
     * bearer, strips nothing, and leaves /login enabled on a head that is still holding a real
     * vendor credential behind a door the caller cannot open.
     */
    @Test
    fun `a client-kind head on a non-passthrough dialect gets NO native client auth`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.OPENAI_RESPONSES),
            controlPort = 3099,
            keyPresent = true,
            forwardClientAuth = false, // what ProviderAssembly actually resolves for this dialect
        )
        assertFalse(
            spec.forwardClientAuth,
            "the recipe must follow the RESOLVED credential, not the declared auth.kind string — " +
                "this head's door stays shut, so its launch must stay a foreign-vendor launch",
        )
    }

    /** The other half: on the arm that really does build a ClientAuthProvider the flag must ride,
     *  or the fix would just be a hardcoded false and the claude head would lose its own login. */
    @Test
    fun `a passthrough client head still gets native client auth`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.ANTHROPIC_PASSTHROUGH),
            controlPort = 3099,
            keyPresent = true,
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

        val spec = factory(tmp).launchSpecFor(ctx, 3099, keyPresent = true, forwardClientAuth = true)
        val cachedWindow = spec.modelOptionsCache.jsonArray.single().jsonObject
            .getValue("context_window").jsonPrimitive.long

        assertEquals(333_000, cachedWindow)
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

        val spec = factory(tmp).launchSpecFor(ctx, 3099, keyPresent = true, forwardClientAuth = true)

        assertEquals(listOf(shown.id), spec.availableModelIds)
        assertEquals(mapOf(shown.id to shown.label), spec.modelLabels)
        assertEquals(mapOf(shown.id to "opus"), spec.modelSlots)
        assertEquals(
            listOf(shown.id),
            spec.modelOptionsCache.jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.content },
        )
    }
}

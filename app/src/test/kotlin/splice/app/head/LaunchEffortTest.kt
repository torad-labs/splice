// Marcos, Oct 28: "effort at high by default, but these should be configurable by users in the console and in the toml
// file", then: "claude code picks should also be considered, for all heads and all adapters we do. Always. WHy one or
// the other, why can't it be both?" Both: a launched session starts at its head's effort (splice.toml, or the
// console's Settings, else high), handed to Claude Code as its own --effort, so the session shows it and /effort
// changes it from there; every adapter then sends the effort Claude Code's request names. A person who gives
// --effort on the command line keeps theirs.
//
// These build a head's launch spec from splice.toml the way the daemon builds it, its knobs read through the same
// layers, and launch it through LaunchService, reading the argv Claude Code is started with.
package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderBuild
import splice.app.provider.UpstreamFaultPlan
import splice.client.ClaudeConfigMaterializer
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.topology.TopologyKnobLayer
import splice.core.turn.WatchdogBudget
import splice.launch.HeadTrees
import splice.launch.LaunchOutcome
import splice.launch.recipe.LaunchService
import splice.topology.TopologyLoader
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val HEAD = "synthetic-gpt"

class LaunchEffortTest {

    @Test
    fun `a head with no effort setting starts Claude Code at high`(@TempDir tmp: Path) {
        val rig = EffortLaunchRig(tmp, settings = "")
        assertEquals(listOf("high"), rig.efforts(emptyList()), "splice's default effort is handed to Claude Code")
    }

    @Test
    fun `a head starts Claude Code at the effort its own overrides set`(@TempDir tmp: Path) {
        val rig = EffortLaunchRig(tmp, settings = "[heads.$HEAD.overrides]\neffort = \"medium\"")
        assertEquals(listOf("medium"), rig.efforts(emptyList()))
    }

    @Test
    fun `a head starts Claude Code at the effort splice toml's defaults set`(@TempDir tmp: Path) {
        val rig = EffortLaunchRig(tmp, settings = "[defaults]\neffort = \"xhigh\"")
        assertEquals(listOf("xhigh"), rig.efforts(emptyList()))
    }

    @Test
    fun `an effort set in the console reaches the next launch without a restart`(@TempDir tmp: Path) {
        val rig = EffortLaunchRig(tmp, settings = "")
        rig.config.patch(mapOf("effort" to "low"))
        assertEquals(listOf("low"), rig.efforts(emptyList()), "the console's Settings write the layer a launch reads")
    }

    @Test
    fun `a person's own --effort is kept and splice adds none`(@TempDir tmp: Path) {
        val rig = EffortLaunchRig(tmp, settings = "[heads.$HEAD.overrides]\neffort = \"medium\"")
        assertEquals(listOf("max"), rig.efforts(listOf("--effort", "max")), "the spaced spelling")
        assertEquals(listOf("--effort=low"), rig.argv(listOf("--effort=low")).filter { it.startsWith("--effort") })
    }

    @Test
    fun `an effort Claude Code has no word for starts nothing of splice's`(@TempDir tmp: Path) {
        val rig = EffortLaunchRig(tmp, settings = "[heads.$HEAD.overrides]\neffort = \"minimal\"")
        assertEquals(emptyList<String>(), rig.efforts(emptyList()), "--effort takes low, medium, high, xhigh or max")
    }
}

/** One head from splice.toml with [settings] after it, its launch spec built as the daemon builds it. */
private class EffortLaunchRig(private val tmp: Path, settings: String) {
    private val topology = TopologyLoader.parse(
        """
        |[providers.$HEAD]
        |dialect = "openai-responses"
        |base_url = "https://example.invalid/v1"
        |auth = { kind = "api-key" }
        |[[providers.$HEAD.models]]
        |id = "gpt-synthetic"
        |context_window = 200000
        |[heads.$HEAD]
        |provider = "$HEAD"
        |port = 31398
        |discovery_prefix = "claude-synthetic--"
        |pinned_model = "gpt-synthetic"
        |$settings
        """.trimMargin(),
    )
    private val paths = StatePaths(baseOverride = tmp.resolve("state"))
    val config = ConfigService(
        paths,
        headOverrides = TopologyKnobLayer(topology).configOverrides(),
        perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
        envReader = { null },
    )

    /** The argv Claude Code is started with when the person gave [extraArgs]. */
    fun argv(extraArgs: List<String>): List<String> {
        val head = topology.heads.getValue(HEAD)
        val providerCfg = topology.providers.getValue(head.provider)
        val planner = SignInPlanner()
        val factory = LaunchSpecFactory(topology, planner, MgmtKey(paths), HeadBuildInputs(config, planner))
        val build = ProviderBuild(
            key = HEAD,
            head = head,
            providerCfg = providerCfg,
            catalog = providerCfg.catalogFor(head),
            cfg = config.getConfig(HEAD),
            faultPlan = UpstreamFaultPlan(WatchdogBudget(60.seconds, 60.seconds, 600.seconds), "$HEAD login"),
        )
        val spec = factory.launchSpecFor(build, 3099, forwardClientAuth = false)
            .let { it.copy(trees = HeadTrees(tmp.resolve(".claude-held"))) }
        val service = LaunchService(ClaudeConfigMaterializer(tmp.resolve("home")), envReader = { null })
        val outcome = service.launchOutcome(spec, extraArgs, dangerouslySkipPermissions = false)
        return (outcome as LaunchOutcome.Ready).recipe.argv
    }

    /** The values given after each `--effort` in the argv. */
    fun efforts(extraArgs: List<String>): List<String> {
        val argv = argv(extraArgs)
        return argv.indices.filter { argv[it] == "--effort" }.mapNotNull { argv.getOrNull(it + 1) }
    }
}

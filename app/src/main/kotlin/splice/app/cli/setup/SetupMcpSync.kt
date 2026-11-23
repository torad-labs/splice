// Opt-in `splice setup` step to bring existing user MCP servers into Splice MCP and sync them to
// whichever harnesses (Claude, Antigravity/agy, Codex) the user wants connected. Completely silent in
// non-TTY/headless mode or when neither external MCP servers nor agy/codex harnesses are detected.
package splice.app.cli.setup

import kotlinx.coroutines.CancellationException
import splice.app.mcp.DiscoveredMcpServer
import splice.app.mcp.McpHarnessDiscovery
import splice.app.mcp.McpHarnessSync
import splice.app.mcp.McpHarnessSyncSeams
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.terminal.MultiSelectOutcome
import splice.terminal.SelectOption
import splice.terminal.WizardFrame
import splice.topology.TopologyStatePaths
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

/** Combined MCP wizard choices held by [SetupCommand]'s plan. */
internal data class McpSetupChoices(
    val share: Boolean?,
    val sync: McpSyncChoice = McpSyncChoice(),
)

/** User's opt-in MCP import and harness sync choices collected during `splice setup`. */
internal data class McpSyncChoice(
    val importServers: List<DiscoveredMcpServer> = emptyList(),
    val harnesses: Set<McpHarness> = emptySet(),
) {
    val active: Boolean get() = importServers.isNotEmpty() || harnesses.isNotEmpty()
}

/** Offers opt-in MCP import and multi-harness sync during interactive `splice setup`. */
internal class SetupMcpSync(
    private val env: EnvReader = EnvReader(System::getenv),
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    /** Prompts interactively only when a TTY is present and external servers or agy/codex harnesses exist. */
    fun offer(prompts: SetupPrompts): McpSyncChoice? {
        if (!prompts.hasConsole()) return McpSyncChoice()
        val discovery = McpHarnessDiscovery(UserHome.dir(env), env, seams.osName)
        val discovered = dedupe(discovery.discover())
        val detected = discovery.detectedHarnesses()
        val hasExternalHarness = McpHarness.AGY in detected || McpHarness.CODEX in detected
        if (discovered.isEmpty() && !hasExternalHarness) return McpSyncChoice()

        val imported = askImport(prompts, discovered)
        val offerHarnesses = imported.isNotEmpty() || hasExternalHarness
        val chosen = if (offerHarnesses) askHarnesses(prompts, detected) else emptySet()
        return chosen?.let { McpSyncChoice(imported, it) }
    }

    fun summaryLines(choice: McpSyncChoice): List<String> = buildList {
        if (choice.importServers.isNotEmpty()) {
            add("Splice MCP: import ${choice.importServers.size} user MCP server(s)")
        }
        if (choice.harnesses.isNotEmpty()) {
            val names = choice.harnesses.sortedBy { it.wire }.joinToString(", ") { it.wire }
            add("MCP harness sync: $names")
        }
    }

    suspend fun apply(choice: McpSyncChoice, frame: WizardFrame) {
        if (!choice.active) return
        val home = UserHome.dir(env)
        val paths = TopologyStatePaths(env, home).current()
        if (choice.importServers.isNotEmpty()) applyImport(choice.importServers, paths, frame)
        if (choice.harnesses.isNotEmpty()) applySync(choice.harnesses, home, paths, frame)
    }

    private fun askImport(prompts: SetupPrompts, discovered: List<DiscoveredMcpServer>): List<DiscoveredMcpServer> {
        if (discovered.isEmpty()) return emptyList()
        val yes = prompts.frame.confirm("Bring ${discovered.size} existing MCP server(s) into Splice MCP?", false)
        return if (yes) discovered else emptyList()
    }

    private fun askHarnesses(prompts: SetupPrompts, detected: Set<McpHarness>): Set<McpHarness>? {
        val options = detected.sortedBy { it.wire }.map { harness ->
            SelectOption(value = harness.wire, label = harness.displayName, hint = harnessHint(harness))
        }
        if (options.isEmpty()) return emptySet()
        return when (val picked = prompts.pickMcpHarnesses(options, emptySet())) {
            is MultiSelectOutcome.Chosen -> picked.values.mapNotNull(::harnessFromWire).toSet()
            MultiSelectOutcome.Cancelled -> null
        }
    }

    private fun applyImport(
        servers: List<DiscoveredMcpServer>,
        paths: splice.core.config.StatePaths,
        frame: WizardFrame,
    ) {
        try {
            val now = Instant.now().toString()
            McpUserRegistry(paths).merge(servers.map { it.toRecord(now) })
            frame.step("Imported ${servers.size} MCP server(s) into Splice MCP")
        } catch (failure: IOException) {
            frame.step("MCP server import skipped (${failure::class.simpleName})")
        }
    }

    private suspend fun applySync(
        harnesses: Set<McpHarness>,
        home: Path,
        paths: splice.core.config.StatePaths,
        frame: WizardFrame,
    ) {
        try {
            val report = McpHarnessSync(home, paths, env, LogSink {}, seams).sync(harnesses)
            report.outcomes.forEach { outcome ->
                val status = if (outcome.ok) "synced" else "skipped"
                frame.step("MCP ${outcome.harness.wire}: $status (${outcome.detail})")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            frame.step("MCP harness sync skipped (${failure::class.simpleName})")
        }
    }

    private fun dedupe(items: List<DiscoveredMcpServer>): List<DiscoveredMcpServer> {
        val seen = LinkedHashMap<String, DiscoveredMcpServer>()
        items.forEach { item -> seen.putIfAbsent(item.name, item) }
        return seen.values.sortedBy { it.name }
    }

    private fun harnessHint(harness: McpHarness): String = when (harness) {
        McpHarness.CLAUDE -> "automatic via Splice launch overlay"
        McpHarness.AGY -> "writes ~/.gemini/config/mcp_config.json"
        McpHarness.CODEX -> "updates ~/.codex/config.toml"
    }

    private fun harnessFromWire(wire: String): McpHarness? = when (wire) {
        McpHarness.CLAUDE.wire -> McpHarness.CLAUDE
        McpHarness.AGY.wire -> McpHarness.AGY
        McpHarness.CODEX.wire -> McpHarness.CODEX
        else -> null
    }
}

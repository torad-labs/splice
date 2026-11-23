// CLI verbs for opt-in user MCP import and multi-harness synchronization:
// `splice mcp status [--json]`, `splice mcp import [--from <src>|all] [--server NAME]...`,
// and `splice mcp sync [--to <harness>|all] [--import]`.
package splice.app.cli.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.McpGlobalRead
import splice.app.cli.AdminSupport
import splice.app.mcp.DiscoveredMcpServer
import splice.app.mcp.McpHarnessCliArgs
import splice.app.mcp.McpHarnessDiscovery
import splice.app.mcp.McpHarnessSync
import splice.app.mcp.McpHarnessSyncSeams
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.topology.TopologyStatePaths
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

private const val FLAG_JSON = "--json"
private const val IMPORT_USAGE =
    "usage: splice mcp import [--from claude-code|claude-desktop|agy|codex|all] [--server NAME]..."

/** Executes `splice mcp status`, `splice mcp import`, and `splice mcp sync`. */
internal class McpHarnessCli(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
    private val homeOverride: Path? = null,
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    private val argParser = McpHarnessCliArgs()

    suspend fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "status" -> runStatus(args.drop(1))
        "import" -> runImport(args.drop(1))
        "sync" -> runSync(args.drop(1))
        else -> {
            err.line("usage: splice mcp $MCP_SERVICE_USAGE")
            2
        }
    }

    private fun runStatus(args: List<String>): Int {
        val asJson = when (args) {
            emptyList<String>() -> false
            listOf(FLAG_JSON) -> true
            else -> {
                err.line("usage: splice mcp status [--json]")
                return 2
            }
        }
        val home = resolveHome()
        val paths = resolvePaths(home)
        val registry = McpUserRegistry(paths)
        val snapshot = registry.read()
        val discovery = McpHarnessDiscovery(home, env, seams.osName)
        val active = McpGlobalRead(home, LogSink {}, TempleMcpEntry(home, env, paths), registry).invoke()
        val discovered = discovery.discover()
        val detected = discovery.detectedHarnesses()
        if (asJson) {
            out.line(renderStatusJson(active, snapshot.harnesses, detected, discovered))
        } else {
            renderStatusText(active, snapshot.harnesses, detected, discovered)
        }
        return 0
    }

    private fun runImport(args: List<String>): Int {
        val parsed = argParser.parseImport(args) ?: run {
            err.line(IMPORT_USAGE)
            return 2
        }
        val home = resolveHome()
        val paths = resolvePaths(home)
        val discovery = McpHarnessDiscovery(home, env, seams.osName)
        val candidates = dedupeDiscovered(discovery.discover(parsed.sources))
        val selected = if (parsed.servers.isEmpty()) {
            candidates
        } else {
            candidates.filter { it.name in parsed.servers }
        }
        if (selected.isEmpty()) {
            out.line("No matching MCP servers found to import.")
            return 0
        }
        val now = Instant.now().toString()
        val registry = McpUserRegistry(paths)
        return try {
            registry.merge(selected.map { it.toRecord(now) })
            out.line("Imported ${selected.size} MCP server(s) into Splice MCP (${registry.file}):")
            selected.forEach { srv ->
                out.line("  ${srv.name} (${transportOf(srv.entry)}, from ${srv.source.wire})")
            }
            out.line("Run `splice mcp sync --to agy,codex` to connect your harnesses.")
            0
        } catch (failure: IOException) {
            err.line("Could not update Splice MCP registry (${failure::class.simpleName}).")
            1
        }
    }

    private suspend fun runSync(args: List<String>): Int {
        val parsed = argParser.parseSync(args) ?: run {
            err.line("usage: splice mcp sync [--to claude|agy|codex|all] [--import]")
            return 2
        }
        val home = resolveHome()
        val paths = resolvePaths(home)
        return try {
            executeSync(parsed, home, paths)
        } catch (failure: IOException) {
            err.line("Could not update Splice MCP registry (${failure::class.simpleName}).")
            1
        }
    }

    private suspend fun executeSync(
        parsed: splice.app.mcp.McpSyncArgs,
        home: Path,
        paths: StatePaths,
    ): Int {
        val discovery = McpHarnessDiscovery(home, env, seams.osName)
        val registry = McpUserRegistry(paths)
        if (parsed.importFirst) {
            val found = dedupeDiscovered(discovery.discover())
            if (found.isNotEmpty()) {
                registry.merge(found.map { it.toRecord(Instant.now().toString()) })
                out.line("Imported ${found.size} MCP server(s) into Splice MCP.")
            }
        }
        val targets = resolveSyncTargets(parsed.harnesses, registry.read().harnesses, discovery.detectedHarnesses())
        if (targets.isEmpty()) {
            out.line("No target harnesses found. Pass --to claude,agy,codex to select harnesses explicitly.")
            return 0
        }
        val report = McpHarnessSync(home, paths, env, LogSink {}, seams).sync(targets)
        out.line("Synced ${report.serverCount} MCP server(s) (${report.hostedCount} hosted via Splice MCP):")
        report.outcomes.forEach { outcome ->
            val mark = if (outcome.ok) "ok" else "failed"
            out.line("  ${outcome.harness.wire}: $mark - ${outcome.detail}")
        }
        return if (report.allOk) 0 else 1
    }

    private fun resolveSyncTargets(
        explicit: Set<McpHarness>,
        remembered: Set<McpHarness>,
        detected: Set<McpHarness>,
    ): Set<McpHarness> = when {
        explicit.isNotEmpty() -> explicit
        remembered.isNotEmpty() -> remembered
        else -> detected
    }

    private fun renderStatusText(
        active: JsonObject,
        connected: Set<McpHarness>,
        detected: Set<McpHarness>,
        discovered: List<DiscoveredMcpServer>,
    ) {
        out.line("Splice MCP servers (${active.size}):")
        if (active.isEmpty()) {
            out.line("  (none)")
        } else {
            active.entries.sortedBy { it.key }.forEach { (name, entry) ->
                val obj = entry as? JsonObject
                out.line("  $name (${obj?.let(::transportOf) ?: "unknown"})")
            }
        }
        val connectedList = connected.sortedBy { it.wire }.joinToString(", ") { it.wire }.ifEmpty { "none" }
        val detectedList = detected.sortedBy { it.wire }.joinToString(", ") { it.wire }.ifEmpty { "none" }
        out.line("Connected harnesses: $connectedList")
        out.line("Detected harnesses: $detectedList")
        val unimported = dedupeDiscovered(discovered).filter { it.name !in active }
        if (unimported.isNotEmpty()) {
            out.line("Available to import (${unimported.size}):")
            unimported.forEach { srv ->
                out.line("  ${srv.name} (${transportOf(srv.entry)}, in ${srv.source.wire})")
            }
        }
    }

    private fun renderStatusJson(
        active: JsonObject,
        connected: Set<McpHarness>,
        detected: Set<McpHarness>,
        discovered: List<DiscoveredMcpServer>,
    ): String = buildJsonObject {
        put(
            "servers",
            buildJsonArray {
                active.entries.sortedBy { it.key }.forEach { (name, entry) ->
                    val obj = entry as? JsonObject
                    addJsonObject {
                        put("name", name)
                        put("transport", obj?.let(::transportOf) ?: "unknown")
                    }
                }
            },
        )
        put(
            "connected_harnesses",
            buildJsonArray { connected.sortedBy { it.wire }.forEach { add(JsonPrimitive(it.wire)) } },
        )
        put(
            "detected_harnesses",
            buildJsonArray { detected.sortedBy { it.wire }.forEach { add(JsonPrimitive(it.wire)) } },
        )
        put(
            "available_to_import",
            buildJsonArray {
                dedupeDiscovered(discovered).filter { it.name !in active }.forEach { srv ->
                    addJsonObject {
                        put("name", srv.name)
                        put("source", srv.source.wire)
                        put("transport", transportOf(srv.entry))
                    }
                }
            },
        )
    }.toString()

    private fun dedupeDiscovered(items: List<DiscoveredMcpServer>): List<DiscoveredMcpServer> {
        val seen = LinkedHashMap<String, DiscoveredMcpServer>()
        items.forEach { item -> seen.putIfAbsent(item.name, item) }
        return seen.values.sortedBy { it.name }
    }

    private fun transportOf(entry: JsonObject): String =
        JsonScalars.str(entry, "type") ?: if (JsonScalars.str(entry, "url") != null) "http" else "stdio"

    private fun resolveHome(): Path = homeOverride ?: AdminSupport.home()

    private fun resolvePaths(home: Path): StatePaths = TopologyStatePaths(env, home).current()
}

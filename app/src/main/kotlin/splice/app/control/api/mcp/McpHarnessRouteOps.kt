// Request parsing, discovery/import/sync orchestration, and credential-safe JSON projection
// for [McpHarnessRoutes]. Never serializes headers, env maps, credentials, or raw URLs.
package splice.app.control.api.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.McpGlobalRead
import splice.app.mcp.DiscoveredMcpServer
import splice.app.mcp.McpHarnessDiscovery
import splice.app.mcp.McpHarnessSync
import splice.app.mcp.McpHarnessSyncSeams
import splice.app.mcp.McpSyncReport
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpRegistryNames
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Path
import java.time.Instant

private const val HARNESS_ALL_TOKEN = "all"

internal class McpHarnessRouteOps(
    private val paths: StatePaths,
    private val home: Path?,
    private val env: EnvReader,
    private val log: LogSink,
    private val seams: McpHarnessSyncSeams,
) {
    private val names = McpRegistryNames()

    fun parseSources(raw: List<String>): List<McpImportSource>? {
        val all = listOf(
            McpImportSource.CLAUDE_CODE,
            McpImportSource.CLAUDE_DESKTOP,
            McpImportSource.AGY,
            McpImportSource.CODEX,
        )
        if (raw.isEmpty()) return all
        val out = mutableListOf<McpImportSource>()
        for (token in raw) {
            if (token.equals(HARNESS_ALL_TOKEN, ignoreCase = true)) {
                out.addAll(all)
            } else {
                val parsed = names.source(token)?.takeIf { it != McpImportSource.MANUAL } ?: return null
                out.add(parsed)
            }
        }
        return out.distinct()
    }

    fun parseHarnesses(raw: List<String>): Set<McpHarness>? {
        if (raw.isEmpty()) return emptySet()
        val out = LinkedHashSet<McpHarness>()
        for (token in raw) {
            if (token.equals(HARNESS_ALL_TOKEN, ignoreCase = true)) {
                out.addAll(McpHarness.entries)
            } else {
                val parsed = names.harness(token) ?: return null
                out.add(parsed)
            }
        }
        return out
    }

    fun performImport(sources: List<McpImportSource>, filter: Set<String>): List<String> {
        val userHome = home ?: UserHome.dir(env)
        val registry = McpUserRegistry(paths)
        registry.readStrict()
        val discovery = McpHarnessDiscovery(userHome, env, seams.osName)
        val candidates = dedupe(discovery.discover(sources))
        val chosen = if (filter.isNotEmpty()) {
            candidates.filter { it.name in filter }
        } else {
            val active = McpGlobalRead(userHome, LogSink {}, TempleMcpEntry(userHome, env, paths), registry).invoke()
            candidates.filter { it.name !in active }
        }
        if (chosen.isEmpty()) return emptyList()
        val now = Instant.now().toString()
        registry.merge(chosen.map { it.toRecord(now) })
        return chosen.map { it.name }
    }

    suspend fun performSync(explicit: Set<McpHarness>, importFirst: Boolean): McpSyncReport? {
        val userHome = home ?: UserHome.dir(env)
        val discovery = McpHarnessDiscovery(userHome, env, seams.osName)
        val registry = McpUserRegistry(paths)
        val existing = registry.readStrict()
        if (importFirst) {
            val active = McpGlobalRead(userHome, LogSink {}, TempleMcpEntry(userHome, env, paths), registry).invoke()
            val found = dedupe(discovery.discover()).filter { it.name !in active }
            if (found.isNotEmpty()) {
                val now = Instant.now().toString()
                registry.merge(found.map { it.toRecord(now) })
            }
        }
        val targets = when {
            explicit.isNotEmpty() -> explicit
            existing.harnesses.isNotEmpty() -> existing.harnesses
            else -> discovery.detectedHarnesses()
        }
        return if (targets.isEmpty()) null else McpHarnessSync(userHome, paths, env, log, seams).sync(targets)
    }

    fun buildSyncBody(report: McpSyncReport): JsonObject = buildJsonObject {
        put("ok", report.allOk)
        put("server_count", report.serverCount)
        put("hosted_count", report.hostedCount)
        putJsonArray("outcomes") {
            report.outcomes.forEach { outcome ->
                addJsonObject {
                    put("harness", outcome.harness.wire)
                    put("ok", outcome.ok)
                    put("changed", outcome.changed)
                    put("server_count", outcome.serverCount)
                    put("hosted_count", outcome.hostedCount)
                    put("detail", outcome.detail)
                }
            }
        }
        report.registrySaveError?.let { why ->
            put("error", "Could not save connected MCP harnesses ($why)")
        }
        put("state", snapshotJson())
    }

    fun snapshotJson(): JsonObject {
        val userHome = home ?: UserHome.dir(env)
        val registry = McpUserRegistry(paths)
        val snapshot = registry.read()
        val discovery = McpHarnessDiscovery(userHome, env, seams.osName)
        val active = McpGlobalRead(userHome, LogSink {}, TempleMcpEntry(userHome, env, paths), registry).invoke()
        val detected = discovery.detectedHarnesses()
        val available = dedupe(discovery.discover()).filter { it.name !in active }
        return buildJsonObject {
            putJsonArray("servers") {
                active.entries.sortedBy { it.key }.forEach { (name, elem) ->
                    val obj = elem as? JsonObject
                    val reg = snapshot.servers[name]
                    addJsonObject {
                        put("name", name)
                        put("transport", obj?.let(::transportOf) ?: "unknown")
                        put("source", reg?.source?.wire ?: "claude-code")
                        put("in_registry", reg != null)
                    }
                }
            }
            putJsonArray("available") {
                available.forEach { srv ->
                    addJsonObject {
                        put("name", srv.name)
                        put("transport", transportOf(srv.entry))
                        put("source", srv.source.wire)
                    }
                }
            }
            putJsonArray("harnesses") {
                McpHarness.entries.forEach { harness ->
                    addJsonObject {
                        put("id", harness.wire)
                        put("label", harness.displayName)
                        put("detected", harness in detected)
                        put("connected", harness in snapshot.harnesses)
                    }
                }
            }
        }
    }

    private fun dedupe(items: List<DiscoveredMcpServer>): List<DiscoveredMcpServer> {
        val seen = LinkedHashMap<String, DiscoveredMcpServer>()
        items.forEach { item -> seen.putIfAbsent(item.name, item) }
        return seen.values.sortedBy { it.name }
    }

    private fun transportOf(entry: JsonObject): String =
        JsonScalars.str(entry, "type") ?: if (JsonScalars.str(entry, "url") != null) "http" else "stdio"
}

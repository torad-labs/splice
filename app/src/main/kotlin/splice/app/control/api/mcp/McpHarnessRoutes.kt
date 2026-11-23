// Management-authenticated control API for the Console's MCP harness UI:
// GET /api/mcp/harnesses, POST /api/mcp/harnesses/import, and POST /api/mcp/harnesses/sync.
// Never returns credential values, headers, env maps, or raw URLs.
package splice.app.control.api.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
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
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

@Serializable
private data class McpHarnessImportRequest(
    val sources: List<String> = emptyList(),
    val servers: List<String> = emptyList(),
)

@Serializable
private data class McpHarnessSyncRequest(
    val harnesses: List<String> = emptyList(),
    val import: Boolean = false,
)

private data class HarnessHttpReply(val status: HttpStatusCode, val body: JsonObject)

private const val HARNESS_ALL_TOKEN = "all"
private const val HARNESS_PERMS_ERROR = "MCP state requires owner-only filesystem permissions"

private class McpHarnessRouteCodec {
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

    fun dedupe(items: List<DiscoveredMcpServer>): List<DiscoveredMcpServer> {
        val seen = LinkedHashMap<String, DiscoveredMcpServer>()
        items.forEach { item -> seen.putIfAbsent(item.name, item) }
        return seen.values.sortedBy { it.name }
    }

    fun transportOf(entry: JsonObject): String =
        JsonScalars.str(entry, "type") ?: if (JsonScalars.str(entry, "url") != null) "http" else "stdio"
}

/** Serves Console reads, user MCP imports, and multi-harness syncs behind [ControlGuard]. */
internal class McpHarnessRoutes(
    private val paths: StatePaths,
    private val home: Path? = null,
    private val env: EnvReader = paths.envReader,
    private val log: LogSink = LogSink {},
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = McpHarnessRouteCodec()
    private val mutation = Mutex()
    private val io = ProcessDispatchers().io()

    suspend fun status(call: ApplicationCall) {
        val state = withContext(io) { snapshotJson() }
        reply(call, state, HttpStatusCode.OK)
    }

    suspend fun importServers(call: ApplicationCall) {
        val req = decodeBody<McpHarnessImportRequest>(call) ?: return
        val sources = codec.parseSources(req.sources)
            ?: return replyError(call, HttpStatusCode.BadRequest, "unknown MCP import source")
        val selectedNames = req.servers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val outcome = mutation.withLock {
            withContext(io) { runImportReply(sources, selectedNames) }
        }
        reply(call, outcome.body, outcome.status)
    }

    suspend fun syncHarnesses(call: ApplicationCall) {
        val req = decodeBody<McpHarnessSyncRequest>(call) ?: return
        val explicit = codec.parseHarnesses(req.harnesses)
            ?: return replyError(call, HttpStatusCode.BadRequest, "unknown MCP harness")
        val outcome = mutation.withLock {
            withContext(io) { runSyncReply(explicit, req.import) }
        }
        reply(call, outcome.body, outcome.status)
    }

    private fun runImportReply(sources: List<McpImportSource>, filter: Set<String>): HarnessHttpReply = try {
        val imported = performImport(sources, filter)
        val body = buildJsonObject {
            put("ok", true)
            put("imported", imported.size)
            put("names", buildJsonArray { imported.forEach { add(JsonPrimitive(it)) } })
            put("state", snapshotJson())
        }
        HarnessHttpReply(HttpStatusCode.OK, body)
    } catch (failure: IOException) {
        errorReply(
            HttpStatusCode.InternalServerError,
            "Could not update Splice MCP registry: ${SafeFailureText.render(failure)}",
        )
    } catch (_: UnsupportedOperationException) {
        errorReply(HttpStatusCode.InternalServerError, HARNESS_PERMS_ERROR)
    }

    private suspend fun runSyncReply(explicit: Set<McpHarness>, importFirst: Boolean): HarnessHttpReply = try {
        val report = performSync(explicit, importFirst)
        if (report == null) {
            errorReply(HttpStatusCode.BadRequest, "no target harnesses detected")
        } else {
            val code = if (report.allOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError
            HarnessHttpReply(code, buildSyncBody(report))
        }
    } catch (failure: IOException) {
        errorReply(
            HttpStatusCode.InternalServerError,
            "Could not sync Splice MCP harnesses: ${SafeFailureText.render(failure)}",
        )
    } catch (_: UnsupportedOperationException) {
        errorReply(HttpStatusCode.InternalServerError, HARNESS_PERMS_ERROR)
    }

    private fun buildSyncBody(report: McpSyncReport): JsonObject = buildJsonObject {
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
        put("state", snapshotJson())
    }

    private fun performImport(sources: List<McpImportSource>, filter: Set<String>): List<String> {
        val userHome = home ?: UserHome.dir(env)
        val registry = McpUserRegistry(paths)
        registry.readStrict()
        val discovery = McpHarnessDiscovery(userHome, env, seams.osName)
        val candidates = codec.dedupe(discovery.discover(sources))
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

    private suspend fun performSync(explicit: Set<McpHarness>, importFirst: Boolean): McpSyncReport? {
        val userHome = home ?: UserHome.dir(env)
        val discovery = McpHarnessDiscovery(userHome, env, seams.osName)
        val registry = McpUserRegistry(paths)
        val existing = registry.readStrict()
        if (importFirst) {
            val active = McpGlobalRead(userHome, LogSink {}, TempleMcpEntry(userHome, env, paths), registry).invoke()
            val found = codec.dedupe(discovery.discover()).filter { it.name !in active }
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

    private fun snapshotJson(): JsonObject {
        val userHome = home ?: UserHome.dir(env)
        val registry = McpUserRegistry(paths)
        val snapshot = registry.read()
        val discovery = McpHarnessDiscovery(userHome, env, seams.osName)
        val active = McpGlobalRead(userHome, LogSink {}, TempleMcpEntry(userHome, env, paths), registry).invoke()
        val detected = discovery.detectedHarnesses()
        val available = codec.dedupe(discovery.discover()).filter { it.name !in active }
        return buildJsonObject {
            putJsonArray("servers") {
                active.entries.sortedBy { it.key }.forEach { (name, elem) ->
                    val obj = elem as? JsonObject
                    val reg = snapshot.servers[name]
                    addJsonObject {
                        put("name", name)
                        put("transport", obj?.let(codec::transportOf) ?: "unknown")
                        put("source", reg?.source?.wire ?: "claude-code")
                        put("in_registry", reg != null)
                    }
                }
            }
            putJsonArray("available") {
                available.forEach { srv ->
                    addJsonObject {
                        put("name", srv.name)
                        put("transport", codec.transportOf(srv.entry))
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

    private suspend inline fun <reified T> decodeBody(call: ApplicationCall): T? = try {
        json.decodeFromString<T>(call.receiveText().ifBlank { "{}" })
    } catch (_: SerializationException) {
        replyError(call, HttpStatusCode.BadRequest, "expected a JSON object")
        null
    } catch (_: IllegalArgumentException) {
        replyError(call, HttpStatusCode.BadRequest, "expected a JSON object")
        null
    }

    private fun errorReply(status: HttpStatusCode, message: String): HarnessHttpReply =
        HarnessHttpReply(status, buildJsonObject { put("error", message) })

    private suspend fun replyError(call: ApplicationCall, status: HttpStatusCode, message: String) =
        reply(call, buildJsonObject { put("error", message) }, status)

    private suspend fun reply(call: ApplicationCall, body: JsonObject, status: HttpStatusCode) {
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }
}

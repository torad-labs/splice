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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mcp.McpHarnessSyncSeams
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpImportSource
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.nio.file.Path

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

private const val HARNESS_PERMS_ERROR = "MCP state requires owner-only filesystem permissions"

/** Serves Console reads, user MCP imports, and multi-harness syncs behind [ControlGuard]. */
internal class McpHarnessRoutes(
    paths: StatePaths,
    home: Path? = null,
    env: EnvReader = paths.envReader,
    log: LogSink = LogSink {},
    seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ops = McpHarnessRouteOps(paths, home, env, log, seams)
    internal val mutation: Mutex = Mutex()
    private val io = ProcessDispatchers().io()

    suspend fun status(call: ApplicationCall) {
        val state = withContext(io) { ops.snapshotJson() }
        reply(call, state, HttpStatusCode.OK)
    }

    suspend fun importServers(call: ApplicationCall) {
        val req = decodeBody<McpHarnessImportRequest>(call) ?: return
        val sources = ops.parseSources(req.sources)
            ?: return replyError(call, HttpStatusCode.BadRequest, "unknown MCP import source")
        val selectedNames = req.servers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val outcome = mutation.withLock {
            withContext(io) { runImportReply(sources, selectedNames) }
        }
        reply(call, outcome.body, outcome.status)
    }

    suspend fun syncHarnesses(call: ApplicationCall) {
        val req = decodeBody<McpHarnessSyncRequest>(call) ?: return
        val explicit = ops.parseHarnesses(req.harnesses)
            ?: return replyError(call, HttpStatusCode.BadRequest, "unknown MCP harness")
        val outcome = mutation.withLock {
            withContext(io) { runSyncReply(explicit, req.import) }
        }
        reply(call, outcome.body, outcome.status)
    }

    private fun runImportReply(sources: List<McpImportSource>, filter: Set<String>): HarnessHttpReply = try {
        val imported = ops.performImport(sources, filter)
        val body = buildJsonObject {
            put("ok", true)
            put("imported", imported.size)
            put("names", buildJsonArray { imported.forEach { add(JsonPrimitive(it)) } })
            put("state", ops.snapshotJson())
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
        val report = ops.performSync(explicit, importFirst)
        if (report == null) {
            errorReply(HttpStatusCode.BadRequest, "no target harnesses detected")
        } else {
            val code = if (report.allOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError
            HarnessHttpReply(code, ops.buildSyncBody(report))
        }
    } catch (failure: IOException) {
        errorReply(
            HttpStatusCode.InternalServerError,
            "Could not sync Splice MCP harnesses: ${SafeFailureText.render(failure)}",
        )
    } catch (_: UnsupportedOperationException) {
        errorReply(HttpStatusCode.InternalServerError, HARNESS_PERMS_ERROR)
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

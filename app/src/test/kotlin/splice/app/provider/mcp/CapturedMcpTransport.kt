package splice.app.provider.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import splice.app.control.ControlAuth
import splice.app.control.ControlRuntime
import splice.app.control.controlServerFor
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.MCP_HEAD_HEADER
import splice.client.mcp.McpSharing
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.mcp.McpToolResultStore

/** The permission client uses the existing HTTP host, with the callback id actually emitted by the head. */
internal class CapturedMcpTransport(paths: StatePaths, store: McpToolResultStore) {
    private val mgmt = MgmtKey(paths)
    private val client = HttpClient(CIO)
    private val global = buildJsonObject { put("capture", CapturedMcpPeer.entry()) }
    private val host = McpHost(
        McpSharing(true, emptySet(), "http://127.0.0.1:0/mcp/", { mgmt.get() }, DirectoryProbe { false }),
        { global },
        McpHostConfig(runtime = McpHostConfig.Runtime(capture = McpHostConfig.Capture(store, Dispatchers.IO))),
        log = { },
    )
    private val control = controlServerFor(
        port = 0,
        heads = emptyMap(),
        config = ConfigService(paths),
        runtime = ControlRuntime(mcpHost = host),
        auth = ControlAuth(mgmtKey = mgmt, log = { }),
    )
    private lateinit var session: String

    suspend fun start() {
        control.start()
        val initialized = post(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put(
                    "params",
                    buildJsonObject {
                        put("protocolVersion", "2025-11-25")
                        put("capabilities", JsonObject(emptyMap()))
                        put("clientInfo", buildJsonObject { put("name", "permission-client"); put("version", "1") })
                    },
                )
            },
        )
        assertEquals(HttpStatusCode.OK, initialized.status, initialized.bodyAsText())
        session = checkNotNull(initialized.headers["Mcp-Session-Id"])
    }

    suspend fun call(callbackId: String?, arguments: JsonObject, name: String = "inspect"): JsonObject =
        reply(callbackId, arguments, name).getValue("result").jsonObject

    suspend fun rpcError(callbackId: String, arguments: JsonObject): JsonObject =
        reply(callbackId, arguments, "inspect").getValue("error").jsonObject

    private suspend fun reply(callbackId: String?, arguments: JsonObject, name: String): JsonObject {
        val response = post(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                put(
                    "params",
                    buildJsonObject {
                        put("name", name)
                        put("arguments", arguments)
                        callbackId?.let { put("_meta", buildJsonObject { put("claudecode/toolUseId", it) }) }
                    },
                )
            },
            session,
        )
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return Json.parseToJsonElement(body).jsonObject
    }

    private suspend fun post(body: JsonObject, sessionId: String? = null) =
        client.post("http://127.0.0.1:${control.listeningPort}/mcp/capture") {
            bearerAuth(mgmt.get())
            sessionId?.let { header("Mcp-Session-Id", it); header("MCP-Protocol-Version", "2025-11-25") }
            header("Accept", "application/json, text/event-stream")
            header(MCP_HEAD_HEADER, "capture-proof")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

    fun close() {
        control.stop()
        host.stop()
        client.close()
    }
}

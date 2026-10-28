package splice.app.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.app.provider.codex.HeadCodeModeControls
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.util.JsonWire
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.diagnostics.CodeModeInterruptControl
import java.nio.file.Path

/** The qualification fixture invokes an actual authenticated HTTP request against the ordinary production route. */
internal class CodeModeHttpInterruptProbe(private val directory: Path) : CodeModeInterruptControl {
    override suspend fun interrupt(
        bridge: CodexCodeModeBridge,
        sessionId: String,
        conversationKey: String,
    ): JsonObject {
        val paths = StatePaths(baseOverride = directory.resolve("interrupt-control"))
        val management = MgmtKey(paths)
        val controls = HeadCodeModeControls().also { it.register(INTERRUPT_PROBE_HEAD, bridge) }
        val server = CodeModeProbeControlServer.build(ConfigService(paths), management, INTERRUPT_PROBE_HEAD)
        server.ports.turns.codeMode = controls
        val body = buildJsonObject { put("conversation_key", conversationKey) }
        val path = "/api/heads/$INTERRUPT_PROBE_HEAD/sessions/${sessionId.encodeURLPathPart()}/interrupt"
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            server.start()
            val url = "http://127.0.0.1:${server.listeningPort}$path"
            val anonymous = post(client, url, body, null)
            val scoped = post(client, url, body, TurnKey(management).get())
            check(anonymous.first == HttpStatusCode.Unauthorized.value) {
                "Anonymous interruption was not refused"
            }
            check(scoped.first == HttpStatusCode.Unauthorized.value) {
                "Session-scoped interruption was not refused"
            }
            val response = post(client, url, body, management.get())
            return buildJsonObject {
                put("boundary", "authenticated-production-control-http")
                put("method", "POST"); put("path", path); put("request", body)
                put("anonymousStatus", anonymous.first); put("sessionKeyStatus", scoped.first)
                put("status", response.first); put("response", response.second)
            }
        } finally {
            controls.remove(INTERRUPT_PROBE_HEAD, bridge)
            server.stop()
            client.close()
        }
    }

    private suspend fun post(
        client: HttpClient,
        url: String,
        body: JsonObject,
        bearer: String?,
    ): Pair<Int, JsonObject> {
        val response = client.post(url) {
            bearer?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(JsonWire.string(body))
        }
        return response.status.value to Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
}

// why: the isolated listener exposes exactly one owned head, never a local daemon or any other conversation.
private const val INTERRUPT_PROBE_HEAD = "native-interrupt-probe"

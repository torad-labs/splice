package splice.app.control.codemode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import splice.app.control.ControlAuth
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
import splice.app.control.controlServerFor
import splice.app.provider.codex.HeadCodeModeControls
import splice.codemode.NativeCodeModeRuntime
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Path

internal class InterruptControlFixture(
    directory: Path,
    private val runtime: NativeCodeModeLifecycle = NativeCodeModeRuntime(),
) {
    private val paths = StatePaths(baseOverride = directory.resolve("state"))
    private val management = MgmtKey(paths)
    private val bridge = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            { runtime },
            CodeModeStateLocation(directory.resolve("records"), directory.resolve("legacy.json")),
        ),
    )
    private val controls = HeadCodeModeControls().also { it.register("owned", bridge) }
    private val server = controlServerFor(
        0,
        mapOf("owned" to head("owned"), "disabled" to head("disabled"), "stopped" to head("stopped", false)),
        ConfigService(paths),
        ControlAuth(management, {}),
    )
    private val client = HttpClient(CIO) { expectSuccess = false }
    val key: String get() = management.get()
    val sessionKey: String get() = TurnKey(management).get()

    suspend fun start() {
        server.ports.turns.codeMode = controls
        server.start()
    }

    fun conversation(key: String, session: String = "client"): InterruptNativeConversation =
        InterruptNativeConversation(bridge, session, key)

    suspend fun interrupt(
        conversation: String,
        session: String = "client",
        head: String = "owned",
        bearer: String? = key,
    ): HttpResponse = post("""{"conversation_key":"$conversation"}""", session, head, bearer)

    suspend fun post(
        body: String,
        session: String = "client",
        head: String = "owned",
        bearer: String? = key,
    ): HttpResponse =
        client.post("http://127.0.0.1:${server.listeningPort}/api/heads/$head/sessions/$session/interrupt") {
            bearer?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    fun close() {
        try {
            server.stop()
        } finally {
            bridge.onProviderStop()
            runtime.close()
            client.close()
        }
    }

    private fun head(headKey: String, running: Boolean = true): ManagedHead = ManagedHead(
        object : Head {
            override val key: String = headKey
            override val label: String = "claude-$headKey"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(running, running, port, "fixture")
        },
        object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe(): AuthDescription = AuthDescription(false, "fixture")
        },
        HeadSources(
            HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
            object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            object : HeadLogSource {
                override fun tail(lines: Int): String = ""
                override fun path(): String = ""
            },
        ),
        UsageWarningSource { UsageWarning(80, 0) },
    )
}

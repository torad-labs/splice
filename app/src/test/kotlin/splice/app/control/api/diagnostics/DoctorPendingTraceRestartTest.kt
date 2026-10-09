package splice.app.control.api.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DoctorWiring
import splice.app.control.ControlAuth
import splice.app.control.ManagedHead
import splice.app.control.controlServerFor
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.util.EnvReader
import splice.diagnostics.doctor.DoctorReport
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

class DoctorPendingTraceRestartTest {
    @Test
    fun `the doctor route reports a trace opt-out as pending restart while config still serves the booted value`(@TempDir tmp: Path) = runBlocking {
        val file = tmp.resolve("splice.toml")
        val initial = topology()
        Files.writeString(file, initial)
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val config = ConfigService(paths, perHeadOverrides = mapOf("local" to mapOf("trace" to "true")))
        val server = controlServerFor(
            port = 0,
            heads = mapOf("local" to head()),
            config = config,
            auth = ControlAuth(mgmtKey = mgmt, log = {}),
        )
        val env = environment(tmp, file, paths)
        val doctor = DoctorWiring.command()
        server.ports.doctor = DoctorReport { answers -> doctor.reportJson(env, answers) }
        server.start()
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            Files.writeString(file, initial.replace("trace = \"true\"", "trace = \"false\""))
            val url = "http://127.0.0.1:${server.listeningPort}"
            val configBody = client.get("$url/api/config?head=local") {
                header("Authorization", "Bearer ${mgmt.get()}")
            }.bodyAsText()
            val effective = Json.parseToJsonElement(configBody).jsonObject.getValue("effective").jsonObject
            assertEquals("true", effective.getValue("trace").toString(), configBody)

            val body = client.get("$url/api/doctor") {
                header("Authorization", "Bearer ${mgmt.get()}")
            }.bodyAsText()
            val rows = Json.parseToJsonElement(body).jsonObject.getValue("checks").jsonArray
            val trace = rows.map { it.jsonObject }
                .single { row -> row.getValue("id").jsonPrimitive.content == "configuration/trace:local" }
            assertEquals("true", trace.getValue("pending_restart").toString(), body)
            assertTrue(
                trace.getValue("detail").jsonPrimitive.content.contains("still writes until the next restart"),
                body,
            )
        } finally {
            client.close()
            server.stop()
        }
    }

    private fun environment(tmp: Path, file: Path, paths: StatePaths): EnvReader = EnvReader { key ->
        when (key) {
            "HOME" -> tmp.toString()
            "SPLICE_CONFIG" -> file.toString()
            "SPLICE_STATE_DIR" -> paths.stateDir.toString()
            else -> null
        }
    }

    private fun topology(): String = """
        [providers.local]
        dialect = "openai-chat"
        base_url = "http://127.0.0.1:9/v1"
        auth = { kind = "api-key", env = "SYNTHETIC_KEY" }
        [[providers.local.models]]
        id = "m"
        context_window = 200000
        [heads.local]
        provider = "local"
        port = 3102
        discovery_prefix = "claude-local--"
        pinned_model = "m"
        [heads.local.overrides]
        trace = "true"
    """.trimIndent() + "\n"

    private fun head(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "local"
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
    )
}

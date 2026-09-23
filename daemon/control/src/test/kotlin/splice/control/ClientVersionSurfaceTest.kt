package splice.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.GATEWAY_VERSION
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.version.ClientVersionTracker
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientVersionSurfaceTest {
    private val client = HttpClient(CIO) { expectSuccess = false }
    private var key = ""

    @AfterAll
    fun tearDown() = client.close()

    @Test
    fun `health stays aggregate while statusline warns once for its session`() = runTest {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("client-version-surface").resolve("state"))
        val port = availablePort()
        val versions = ClientVersionTracker(testedVersion = "2.1.257")
        versions.observe("session-new", "claude-cli/2.1.258")
        versions.observe("session-equal", "claude-cli/2.1.257")
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val server = ControlServer(
            port = port,
            heads = mapOf("test" to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "" },
            log = {},
            clientVersions = versions,
        )
        server.start() // routed and bound before it returns: Ktor's default SEQUENTIAL startup (V4-139)
        try {
            val expected =
                "Claude Code 2.1.258 is newer than the version splice $GATEWAY_VERSION was tested with (2.1.257)"
            assertEquals(expected, healthWarning(port))

            val first = statusline(port, "session-new")
            val second = statusline(port, "session-new")
            val equal = statusline(port, "session-equal")
            assertTrue(first.contains(expected), first)
            assertFalse(second.contains(expected), second)
            assertFalse(equal.contains("newer than"), equal)
            assertEquals(expected, healthWarning(port), "statusline must not consume the aggregate surface")
        } finally {
            server.stop()
        }
    }

    private suspend fun healthWarning(port: Int): String? {
        val body = client.get("http://127.0.0.1:$port/health").bodyAsText()
        return Json.parseToJsonElement(body).jsonObject["clientVersionWarning"]?.jsonPrimitive?.content
    }

    private suspend fun statusline(port: Int, session: String): String =
        client.post("http://127.0.0.1:$port/statusline/test") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody("""{"session_id":"$session","model":{"id":"model","display_name":"Model"}}""")
        }.bodyAsText()

    private fun managedHead() = ManagedHead(
        head = object : Head {
            override val key: String = "test"
            override val label: String = "Test"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot() = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test")
        },
        usage = HeadUsageSource { UsageView(0L, 0, null) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int) = ""
            override fun path() = "/tmp/client-version-surface.log"
        },
        warnPct = 80,
        warnTokens5h = 0,
    )

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
}

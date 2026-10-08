// NEW: actual authenticated control routes share the same activity store as the session registry.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.util.WallClock
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.sessions.registry.ForegroundTools
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

private const val NOW = 1_800_000_000_000L
private const val SESSION = "synthetic-session"
private const val START = """{"session_id":"synthetic-session","tool_use_id":"tool-1","phase":"start"}"""

class ForegroundHookWiringTest {
    @Test
    fun `the real route is session-guarded and updates the registry store only after wiring`(@TempDir dir: Path) {
        var now = NOW
        val tools = ForegroundTools(clock = { now })
        val registry = registry(dir, tools, WallClock { now })
        val paths = StatePaths(baseOverride = dir.resolve("state"))
        val mgmt = MgmtKey(paths)
        val server = controlServerFor(
            port = 0,
            heads = mapOf("synthetic" to head()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            log = {},
            runtime = ControlRuntime(sessions = registry),
        )
        runBlocking { server.start() }
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(10_000) {
                    val url = "http://127.0.0.1:${server.listeningPort}/hooks/foreground/synthetic"
                    val anonymous = client.post(url) { setBody(START) }
                    assertEquals(401, anonymous.status.value)
                    assertEquals(SessionAvailability.STALE, registry.read().single().availability)
                    val beforeWiring = callback(client, url, mgmt, START)
                    assertEquals(200, beforeWiring.status.value, beforeWiring.bodyAsText())
                    assertEquals(SessionAvailability.STALE, registry.read().single().availability)
                    server.ports.foreground = tools
                    val accepted = callback(client, url, mgmt, START)
                    assertEquals(200, accepted.status.value, accepted.bodyAsText())
                    now += 45 * 60_000L
                    assertEquals(SessionAvailability.LIVE, registry.read().single().availability)
                    val ended = client.post(url) {
                        header("Authorization", "Bearer ${TurnKey(mgmt).get()}")
                        header(FOREGROUND_OWNER_HEADER, "11111111-0000-4000-8000-000000000001")
                        setBody("""{"session_id":"$SESSION","tool_use_id":null,"phase":"session_end"}""")
                    }
                    assertEquals(200, ended.status.value)
                    assertEquals(SessionAvailability.STALE, registry.read().single().availability)
                }
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun `the daemon wires both sides to one store and losing either link fails the pin`() {
        val relative = "app/src/main/kotlin/splice/app/ControlPlane.kt"
        var root: Path? = Path.of("").toAbsolutePath()
        while (root != null && !Files.exists(root.resolve(relative))) root = root.parent
        val source = Files.readString(requireNotNull(root).resolve(relative))
        val links = listOf(
            "internal val foregroundTools = splice.sessions.registry.ForegroundTools()",
            "foreground = foregroundTools,",
            "srv.ports.foreground = foregroundTools",
        )
        assertTrue(links.all(source::contains))
        links.forEach { missing -> assertFalse(links.all(source.replace(missing, "")::contains), missing) }
    }

    private suspend fun callback(client: HttpClient, url: String, mgmt: MgmtKey, body: String) =
        client.post(url) {
            header("Authorization", "Bearer ${TurnKey(mgmt).get()}")
            header(FOREGROUND_OWNER_HEADER, "11111111-0000-4000-8000-000000000001")
            setBody(body)
        }

    private fun registry(dir: Path, tools: ForegroundTools, clock: WallClock): SessionRegistry {
        val rows = Files.createDirectories(dir.resolve("sessions"))
        Files.writeString(
            rows.resolve("11.json"),
            """{"pid":11,"sessionId":"$SESSION","updatedAt":${NOW - 43_200_000L}}""",
        )
        return SessionRegistry(
            rows,
            routeOf = { SessionRoute.Unknown },
            pidAlive = { true },
            pidStartedAt = { null },
            clock = clock,
            foreground = tools,
        )
    }

    private fun head(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "synthetic"
            override val label: String = "synthetic"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "fixture")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "fixture", emptyMap())
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

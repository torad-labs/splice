// NEW: log-tail-copies review (2026-10-25) — both log routes through a REAL ControlServer whose daemon.log path holds a
// line feed. The routes always cut a log's in-band notice at each line feed, one JSON entry per piece, and the Models
// page draws one row per entry. The lean tail sent the notice as one entry, which changed both routes' JSON.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.LogFileSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Path

class LogRoutesNoticeTest {
    /** The codex head with its log wired as ManagedHeadFactory wires it: daemon.log, filtered by the head's tag. */
    private fun codexHead(log: Path): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "codex"
            override val label: String = "codex"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
        },
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            logs = LogFileSource(log, "[codex]"),
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
    )

    @Test
    fun `both log routes send an absent log's notice cut at the line feed its path holds`(
        @TempDir tmp: Path,
    ) = runTest {
        val paths = StatePaths(baseOverride = tmp.resolve("a\nb").resolve("state"))
        val heads = mapOf("codex" to codexHead(paths.logsDir.resolve("daemon.log")))
        val mgmt = MgmtKey(paths)
        val bearer = mgmt.get()
        val control = controlServerWith(
            health = healthFor(heads),
            heads = heads,
            config = ConfigService(paths),
            auth = ControlAuth(mgmtKey = mgmt, log = {}),
        )
        val client = HttpClient(CIO) { expectSuccess = false }
        control.start()
        try {
            suspend fun body(route: String): String {
                val response = client.get("http://127.0.0.1:${control.listeningPort}$route") {
                    header("Authorization", "Bearer $bearer")
                }
                return String(response.bodyAsBytes(), Charsets.UTF_8)
            }
            val path = "$tmp/a\\nb/logs/daemon.log"
            val first = "[logs unavailable: $tmp/a"
            val second = "b/logs/daemon.log does not exist yet; the daemon creates it when it starts]"
            val lines = """["$first","$second"]"""
            assertEquals("""{"path":"$path","lines":$lines}""", body("/api/logs"))
            assertEquals("""{"key":"codex","path":"$path","lines":$lines}""", body("/api/logs/codex"))
        } finally {
            control.stop()
            client.close()
        }
    }
}

// NEW: V4-444 — the real guarded console route probes without a turn and reuses retained observations.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.sources.UsageStoreSource
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaSnapshotSink
import java.nio.file.Path

class QuotaOpenControlTest {
    @Test
    fun `guarded console probe fires once and a floor reuse retains its date`(@TempDir root: Path) = runTest {
        val paths = StatePaths(baseOverride = root)
        val mgmt = MgmtKey(paths)
        val observed = System.currentTimeMillis()
        var elapsed = 0L
        var probes = 0
        val tracker = QuotaTracker(paths.quotaFile("synthetic"))
        val poller = poller(backgroundScope, tracker, observed, ElapsedClock { elapsed }) { probes++ }
        val usage = UsageStoreSource(
            UsageStore(paths.usageFile("synthetic"), paths.ratelimitFile("synthetic")),
            tracker,
            listOf(poller),
        )
        val server = controlServerFor(
            0,
            mapOf("synthetic" to managed(usage)),
            ConfigService(paths),
            mgmt,
            { },
        )
        val client = HttpClient(CIO)
        server.start()
        try {
            val url = "http://127.0.0.1:${server.listeningPort}/api/usage/probe"
            assertEquals(HttpStatusCode.Unauthorized, client.post(url).status)
            assertEquals(0, probes, "a refused management request cannot contact a provider")
            val first = client.post(url) { header("Authorization", "Bearer ${mgmt.get()}") }
            assertEquals(HttpStatusCode.OK, first.status)
            val window = quotaWindow(first.bodyAsText())
            assertEquals("25", window.getValue("used_pct").jsonPrimitive.content)
            assertEquals((observed / 1000).toString(), window.getValue("observed_at").jsonPrimitive.content)
            assertEquals(1, probes)
            elapsed += 1000
            assertEquals(
                HttpStatusCode.OK,
                client.post(url) { header("Authorization", "Bearer ${mgmt.get()}") }.status,
            )
            assertEquals(1, probes, "another page inside the floor reuses the provider answer")
            assertEquals(observed, tracker.snapshot()?.updatedAt, "reuse does not retimestamp the observation")
        } finally {
            server.stop()
            client.close()
        }
    }

    private fun poller(
        scope: CoroutineScope,
        tracker: QuotaTracker,
        observed: Long,
        clock: ElapsedClock,
        count: () -> Unit,
    ): QuotaPoller = QuotaPoller(
        scope,
        "synthetic",
        QuotaProbe {
            count()
            reading(observed)
        },
        QuotaSnapshotSink(tracker::record),
        { },
        elapsedClock = clock,
    )

    private fun quotaWindow(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("heads")
        .jsonArray.single().jsonObject.getValue("usage").jsonObject
        .getValue("quota").jsonObject.getValue("five_hour").jsonObject

    private fun reading(observed: Long): QuotaSnapshot =
        QuotaSnapshot(fiveHour = QuotaWindow(25.0, observed / 1000 + 3600, 18_000), updatedAt = observed)

    private fun managed(usage: UsageStoreSource): ManagedHead = ManagedHead(
        head = object : Head {
            override val key = "synthetic"
            override val label = "Synthetic"
            override val port = 0
            override suspend fun start() = error("quota probing must not start a model turn")
            override suspend fun stop() = Unit
            override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = 0, version = "synthetic")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "synthetic")
        },
        usage = usage,
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int) = ""
            override fun path() = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
    )
}

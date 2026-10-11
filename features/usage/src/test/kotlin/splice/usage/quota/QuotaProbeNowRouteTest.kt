// NEW: V4-444 — opening a quota surface probes without a turn and reuses an honestly dated snapshot.
package splice.usage.quota

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindow
import splice.core.usage.QuotaWindowView
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import java.nio.file.Path

class QuotaProbeNowRouteTest {
    @Test
    fun `a page-open request probes and the next request reuses its observation`(@TempDir root: Path) =
        testApplication {
            var now = 1_788_000_000_000L
            var calls = 0
            var recorded: QuotaSnapshot? = null
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val poller = QuotaPoller(
                scope,
                "synthetic",
                QuotaProbe {
                    calls++
                    QuotaSnapshot(
                        fiveHour = QuotaWindow(25.0, now / 1_000 + 3_600, 18_000),
                        updatedAt = now,
                    )
                },
                QuotaSnapshotSink { recorded = it },
                { },
                clocks = QuotaClocks(wall = WallClock { now }, elapsed = ElapsedClock { now }),
            )
            val source = object : HeadUsageSource {
                override fun snapshot(): UsageView {
                    val snapshot = recorded
                    val window = snapshot?.fiveHour?.let {
                        QuotaWindowView(it.usedPercent.toInt(), it.resetsAt, snapshot.observedAtEpochSeconds)
                    }
                    return UsageView(0, 0, null, window?.let { QuotaView(it, null, null) })
                }

                override suspend fun probeNow() {
                    poller.probeNow()
                }
            }
            val payloads = UsagePayloads(
                UsageHeads { listOf(UsageHead("synthetic", "claude-synthetic", source, UsageHeadWarn(80, 0))) },
                ConfigService(StatePaths(baseOverride = root)),
                WallClock { now },
            )
            application {
                routing { post("/api/usage/probe") { call.respondText(payloads.probeNowJson()) } }
            }
            try {
                val first = client.post("/api/usage/probe")
                assertEquals(200, first.status.value)
                val body = Json.parseToJsonElement(first.bodyAsText()).jsonObject
                val window = body.getValue("heads").jsonArray.single().jsonObject
                    .getValue("usage").jsonObject.getValue("quota").jsonObject.getValue("five_hour").jsonObject
                assertEquals("25", window.getValue("used_pct").jsonPrimitive.content)
                assertEquals("1788000000", window.getValue("observed_at").jsonPrimitive.content)
                assertEquals(1, calls, "opening the page must probe without a turn")
                now += 1_000
                assertEquals(200, client.post("/api/usage/probe").status.value)
                assertEquals(1, calls, "opening again inside the floor must not hit the provider")
                assertEquals(1_788_000_000_000L, recorded?.updatedAt, "reuse never retimestamps the observation")
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
}

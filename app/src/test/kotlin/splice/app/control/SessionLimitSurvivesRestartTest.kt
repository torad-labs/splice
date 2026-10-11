// A session at its plan's limit still reads At limit after the daemon restarts: the ending is seeded from the tail of
// the head's own perf rows, which the restart left on disk, so the page does not turn into Idle until the session sends
// again. The restart here is a PerfStats built over a file that an earlier daemon wrote; the listing is the real
// /api/sessions over HTTP.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.sources.PerfStatsSource
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.perf.PerfStats
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val LIMITED = "a1a1a1a1-0000-4000-8000-000000000001"
private const val LIMIT_CLEARED = "b2b2b2b2-0000-4000-8000-000000000002"
private const val WENT_THROUGH = "c3c3c3c3-0000-4000-8000-000000000003"
private const val NOW_MS = 1_791_690_000_000L
private const val HOUR_S = 3_600L
private const val WAIT_MS = 30_000L
private const val POLL_MS = 20L

class SessionLimitSurvivesRestartTest {
    @TempDir
    lateinit var tmp: Path

    private fun row(session: String, outcome: String, ts: Long, resetEpochSeconds: Long? = null): String = buildString {
        append("""{"ts":$ts,"model":"gpt-5.6-sol","outcome":"$outcome","compact":false,""")
        append(""""session":"${session.take(8)}",""")
        append(""""account":"Torad"""")
        resetEpochSeconds?.let { append(""","earliest_reset_epoch_seconds":$it""") }
        append("}")
    }

    private fun listing(rowsBeforeRestart: List<String>): Map<String, JsonObject> {
        val perf = tmp.resolve("claudex-perf.jsonl")
        Files.writeString(perf, rowsBeforeRestart.joinToString("\n", postfix = "\n"))
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        listOf(LIMITED, LIMIT_CLEARED, WENT_THROUGH).forEachIndexed { at, id ->
            val row = """{"pid":${at + 1},"sessionId":"$id","updatedAt":$NOW_MS,"version":"2.1.289"}"""
            Files.writeString(sessions.resolve("${at + 1}.json"), row)
        }
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        // the restart: a fresh PerfStats over the file the earlier daemon left, with nothing noted in memory
        val control = controlServerFor(
            port = 0,
            heads = mapOf("claudex" to managed(PerfStatsSource(PerfStats(perf)))),
            config = ConfigService(paths),
            runtime = ControlRuntime(
                sessions = SessionRegistry(
                    sessionsDir = sessions,
                    routeOf = { SessionRoute.Unknown },
                    pidAlive = { true },
                    clock = { NOW_MS },
                ),
            ),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        runBlocking { control.start() }
        val client = HttpClient(CIO)
        try {
            return runBlocking {
                withTimeout(WAIT_MS) {
                    while (runCatching { Socket("127.0.0.1", control.listeningPort).close() }.isFailure) {
                        delay(POLL_MS)
                    }
                    val body = client.get("http://127.0.0.1:${control.listeningPort}/api/sessions") {
                        header("Authorization", "Bearer ${mgmt.get()}")
                    }.bodyAsText()
                    Json.parseToJsonElement(body).jsonObject.getValue("sessions").jsonArray
                        .map { it.jsonObject }
                        .associateBy { it.getValue("session_id").jsonPrimitive.content }
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    @Test
    fun `a session whose last turn ended at its limit reads At limit with its reset time after a restart`() {
        val now = System.currentTimeMillis()
        val resetS = now / 1_000 + HOUR_S
        val listed = listing(
            listOf(
                row(LIMITED, "ok", now - 120_000),
                row(LIMITED, "error:plan-limit", now - 60_000, resetS),
                row(LIMIT_CLEARED, "error:plan-limit", now - 90_000, now / 1_000 - 30),
                row(WENT_THROUGH, "error:plan-limit", now - 90_000, resetS),
                row(WENT_THROUGH, "ok", now - 30_000),
            ),
        )

        val ended = listed.getValue(LIMITED).getValue("ended_by").jsonObject
        assertEquals("error:plan-limit", ended.getValue("outcome").jsonPrimitive.content)
        assertEquals("Torad", ended.getValue("account").jsonPrimitive.content)
        assertEquals(resetS * 1_000, ended.getValue("reset_ms").jsonPrimitive.longOrNull)
        assertNull(listed.getValue(LIMIT_CLEARED)["ended_by"], "a window whose reset has passed is not At limit")
        assertNull(listed.getValue(WENT_THROUGH)["ended_by"], "a request that went through after the limit clears it")
    }

    private fun managed(perf: PerfStatsSource): ManagedHead = ManagedHead(
        head = object : Head {
            override val key = "claudex"
            override val label = "Claudex"
            override val port = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot() = HeadHealth(true, true, 0, "claudex")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "claudex", emptyMap())
        },
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0, 0, null) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int) = ""
                override fun path() = ""
            },
            perf = perf,
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
    )
}

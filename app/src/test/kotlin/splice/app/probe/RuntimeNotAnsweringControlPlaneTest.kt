// The runtime-reach readings the daemon holds reach /health and the heads route through the real control plane,
// and a change to them reaches both on the next request.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.ControlPlane
import splice.app.DaemonEnvironment
import splice.app.control.FailedHeads
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
import splice.app.head.HeadProbeReadings
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
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path

class RuntimeNotAnsweringControlPlaneTest {

    /** The readings the watch holds reach both routes, and a change to them reaches both: /health and the heads
     *  route each read the same probe at request time, so a second, empty signal set fails here on /api/heads. */
    @Test
    fun `the readings the watch holds reach the health body and the heads route, and a change to them reaches both`(
        @TempDir dir: Path,
    ) = runTest {
        val paths = StatePaths(baseOverride = dir.resolve("state"))
        val probes = ScriptedProbes()
        val plane = ControlPlane(DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }), { })
        val server = plane.start(
            controlPort = 0,
            heads = mapOf("test" to managedHead()),
            failedHeads = FailedHeads { 0 },
            headCount = 1,
            probes = probes,
        ) ?: error("the control plane did not bind")
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            val key = MgmtKey(paths).get()
            val port = server.listeningPort
            for (silent in listOf("http://127.0.0.1:9/first", "http://127.0.0.1:9/second")) {
                probes.silent = mapOf("test" to silent)
                assertEquals(silent, silentInHealth(client, port), "/health must show the reading the watch holds")
                assertEquals(silent, silentInHeads(client, port, key), "/api/heads must show the same reading")
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    private suspend fun silentInHealth(client: HttpClient, port: Int): String? {
        val body = Json.parseToJsonElement(client.get("http://127.0.0.1:$port/health").bodyAsText()).jsonObject
        return body["runtimeNotAnswering"]?.jsonObject?.get("test")?.jsonPrimitive?.content
    }

    private suspend fun silentInHeads(client: HttpClient, port: Int, key: String): String? {
        val body = client.get("http://127.0.0.1:$port/api/heads") { header("Authorization", "Bearer $key") }
            .bodyAsText()
        return rowFor(Json.parseToJsonElement(body), "test")?.get("runtimeNotAnswering")?.jsonPrimitive?.content
    }

    /** The first object whose `key` is [key]: the head's own row, wherever the listing nests it. */
    private fun rowFor(element: JsonElement, key: String): JsonObject? = when (element) {
        is JsonObject -> element.takeIf { it["key"]?.jsonPrimitive?.content == key }
            ?: element.values.firstNotNullOfOrNull { rowFor(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { rowFor(it, key) }
        is JsonPrimitive -> null
    }

    private class ScriptedProbes : HeadProbeReadings {
        @Volatile
        var silent: Map<String, String> = emptyMap()

        override fun stalledKeys(): List<String> = emptyList()

        override fun runtimeNotAnswering(): Map<String, String> = silent
    }

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
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0L, 0, null) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int) = ""
                override fun path() = "/tmp/runtime-watch-wiring.log"
            },
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
    )
}

// 2026-11-09 audit: a head refusing every request reads so where the operator looks: /health's refusingHeads, the
// heads route, and the status table's row. A head that admits carries nothing, and /health's ok and counts stay theirs.
package splice.app.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.status.StatusReadings
import splice.app.cli.status.StatusTable
import splice.app.control.api.HeadResolver
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.head.RefusingReading
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

private const val REASON = "the request carries `x`: claudex cannot carry a request field it does not recognize, " +
    "so the turn was not sent"

private class RefusingHead(override val key: String, private val reading: RefusingReading?) : Head {
    override val label = key
    override val port = 0
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = 0, version = "kt-1")
    override fun refusing(): RefusingReading? = reading
}

class RefusingHeadSurfacesTest {
    private fun managed(head: Head) = ManagedHead(
        head = head,
        auth = object : splice.core.auth.AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = splice.core.auth.AuthDescription(false, "x", emptyMap())
        },
        sources = HeadSources(
            usage = object : HeadUsageSource {
                override fun snapshot() = UsageView(0L, 0, RateLimitView(0, 0, "0s"))
            },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int) = ""
                override fun path() = "/tmp/x.log"
            },
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
        authSurface = HeadAuthSurface(authKind = "x"),
    )

    private val heads = listOf(
        RefusingHead("claudex", RefusingReading(REASON, 7, 1_000L)),
        RefusingHead("grok", null),
    ).associate { it.key to managed(it) }

    private val signals = signalsFor(heads)

    @Test
    fun `health names the refusing head with its reason, run and start, and keeps ok and its counts`() {
        val health = Json.parseToJsonElement(healthFor(heads, signals = signals).json(0L)).jsonObject
        val refusing = health.getValue("refusingHeads").jsonObject

        assertEquals(setOf("claudex"), refusing.keys)
        val run = refusing.getValue("claudex").jsonObject
        assertEquals(REASON, run.getValue("reason").jsonPrimitive.content)
        assertEquals(7, run.getValue("refusedInRow").jsonPrimitive.int)
        assertEquals(1_000L, run.getValue("sinceEpochMillis").jsonPrimitive.long)
        assertTrue(health.getValue("ok").jsonPrimitive.boolean)
        assertEquals(2, health.getValue("readyHeads").jsonPrimitive.int)
    }

    @Test
    fun `the heads route carries the run on the refusing head alone`() {
        val byKey: Map<String, JsonObject> = HeadResolver(heads, signals).headStatuses(0L)
            .associateBy { it.getValue("key").jsonPrimitive.content }

        val run = byKey.getValue("claudex").getValue("refusing").jsonObject
        assertEquals(REASON, run.getValue("reason").jsonPrimitive.content)
        assertNull(byKey.getValue("grok")["refusing"])
    }

    @Test
    fun `the status row of a refusing head says so in place of ready`(@TempDir bin: Path) {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://api.example.invalid/v1",
            auth = AuthConfig(kind = "api-key", env = "TEST_KEY"),
        )
        val head = HeadConfig(
            provider = "p",
            port = 3099,
            discoveryPrefix = "claudex--",
            pinnedModel = "m",
            claude = ClaudeWrapperConfig(command = "claudex"),
        )
        Files.createSymbolicLink(bin.resolve("claudex"), bin.resolve("t"))
        val vars = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_KEY" to "k")

        val row = StatusTable(CliPalette(ColorDepth.NONE))
            .lines(
                Topology(providers = mapOf("p" to provider), heads = mapOf("claudex" to head)),
                EnvReader(vars::get),
                StatusReadings(refusing = mapOf("claudex" to REASON)),
            )
            .single { it.contains("claudex") && it.contains("3099") }

        assertTrue(row.contains("refusing every turn: $REASON"), row)
        assertFalse(row.contains("ready"), row)
    }
}

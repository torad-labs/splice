package splice.app.cli.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.UsageWarning
import splice.app.control.healthFor
import splice.app.control.readinessFor
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import splice.daemonclient.DaemonProbe
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

private const val SIX_DAYS_MS = 6L * 24 * 60 * 60 * 1_000
private val NOW_MS = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()
private val RESET_SECONDS = (NOW_MS + SIX_DAYS_MS) / 1_000

private class RefusingHead(var remainingMs: Long) : Head {
    override val key = "claude-muse"
    override val label = key
    override val port = 3103
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = port, version = "test")
    override fun providerResetForMs(): Long = remainingMs
}

class ProviderResetStatusTest {
    private val topology = Topology(
        providers = mapOf(
            "muse" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.invalid",
                auth = AuthConfig(kind = "api-key", env = "TEST_MUSE_KEY"),
            ),
        ),
        heads = mapOf(
            "claude-muse" to HeadConfig(
                provider = "muse",
                port = 3103,
                discoveryPrefix = "muse--",
                pinnedModel = "m",
                claude = ClaudeWrapperConfig(command = "claude-muse"),
            ),
        ),
    )

    @Test
    fun `a six-day provider reset replaces ready with its deadline in the machine's zone`(@TempDir bin: Path) {
        Files.createSymbolicLink(bin.resolve("claude-muse"), bin.resolve("target"))
        val vars = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_MUSE_KEY" to "synthetic-key")
        val env = EnvReader(vars::get)
        // The zone is the machine's, so this pins Chicago explicitly instead of hoping the runner is there.
        val chicago = LocalTimeText(ZoneId.of("America/Chicago"))
        val table = StatusTable(CliPalette(ColorDepth.NONE), WallClock { NOW_MS }, chicago)
        val limited = table.lines(
            topology,
            env,
            StatusReadings(quotaResetAtEpochSeconds = mapOf("claude-muse" to RESET_SECONDS)),
        )[1]
        assertTrue(limited.contains("out of quota until Oct 4, 7:00 PM CDT"), limited)
        assertFalse(limited.contains("ready"), limited)
        val ready = table.lines(topology, env)[1]
        assertTrue(ready.trimEnd().endsWith("ready"), ready)
        val expired = table.lines(
            topology,
            env,
            StatusReadings(quotaResetAtEpochSeconds = mapOf("claude-muse" to NOW_MS / 1_000)),
        )[1]
        assertTrue(expired.trimEnd().endsWith("ready"), expired)
    }

    @Test
    fun `health names the live reset per head and drops it once the provider recovers`() {
        val head = RefusingHead(SIX_DAYS_MS)
        val source = ManagedHead(
            head = head,
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() = AuthDescription(false, "synthetic", emptyMap())
            },
            sources = HeadSources(
                usage = HeadUsageSource { UsageView(0, 0, null) },
                compact = object : HeadCompactSource {
                    override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
                },
                logs = object : HeadLogSource {
                    override fun tail(lines: Int) = ""
                    override fun path() = "/synthetic/daemon.log"
                },
            ),
            usageWarning = UsageWarning(warnPct = 80, warnTokens5h = 0),
        )
        val heads = mapOf(head.key to source)
        val payloads = healthFor(heads, readinessFor(heads, configuredHeads = 1))
        val body = payloads.json(NOW_MS)
        val health = Json.parseToJsonElement(body).jsonObject
        assertEquals("0", health.getValue("failedHeads").jsonPrimitive.content)
        assertEquals(
            RESET_SECONDS.toString(),
            health.getValue("quotaResetAtEpochSeconds").jsonObject.getValue(head.key).jsonPrimitive.content,
        )
        assertEquals(RESET_SECONDS, DaemonProbe.parseHealth(body).quota.refusedUntil[head.key])
        head.remainingMs = 0
        val ready = payloads.json(NOW_MS)
        assertNull(Json.parseToJsonElement(ready).jsonObject["quotaResetAtEpochSeconds"])
        assertEquals(emptyMap<String, Long>(), DaemonProbe.parseHealth(ready).quota.refusedUntil)
    }
}

package splice.usage.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import java.nio.file.Path
import java.time.Instant

private const val SIX_DAYS_MS = 6L * 24 * 60 * 60 * 1_000
private val NOW_MS = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()

class ProviderResetUsageTest {
    @Test
    fun `provider reset makes a head with no quota headers critically exhausted until it passes`(@TempDir root: Path) {
        var remainingMs = SIX_DAYS_MS
        val head = UsageHead(
            key = "claude-muse",
            label = "claude-muse",
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        )
        val sources = object : UsageHeads {
            override fun all() = listOf(head)
            override fun providerResetForMs(key: String) = if (key == head.key) remainingMs else 0L
        }
        val payload = UsagePayloads(
            sources,
            ConfigService(StatePaths(baseOverride = root.resolve("state"))),
            WallClock { NOW_MS },
        )
        fun warn() = Json.parseToJsonElement(payload.usageJson()).jsonObject
            .getValue("heads").jsonArray.single().jsonObject.getValue("usage").jsonObject
            .getValue("warn").jsonObject
        val limited = warn()
        assertEquals("critical", limited.getValue("level").jsonPrimitive.content)
        assertEquals("100", limited.getValue("pct").jsonPrimitive.content)
        assertEquals("provider_reset", limited.getValue("source").jsonPrimitive.content)
        val resetAt = Instant.ofEpochMilli(NOW_MS + SIX_DAYS_MS).toString()
        assertEquals(resetAt, limited.getValue("reset").jsonPrimitive.content)
        remainingMs = 0L
        val ready = warn()
        assertEquals("ok", ready.getValue("level").jsonPrimitive.content)
        assertEquals("none", ready.getValue("source").jsonPrimitive.content)
    }
}

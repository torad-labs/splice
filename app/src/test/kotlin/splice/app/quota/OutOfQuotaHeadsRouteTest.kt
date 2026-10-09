// The heads route carries each running head's out-of-quota instant in epoch seconds (the figure /health carries),
// a head that is not refusing or not running carries none, and /health's ready and failed counts are unchanged.
package splice.app.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.control.HeadAuthSurface
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.UsageWarning
import splice.app.control.api.HeadResolver
import splice.app.control.healthFor
import splice.app.control.signalsFor
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView

private const val FIELD = "quotaResetAtEpochSeconds"
private const val NOW_MS = 1_790_000_000_000L
private const val THREE_DAYS_MS = 3 * 86_400_000L

private class ProviderHead(
    override val key: String,
    private val running: Boolean = true,
    private val refusedForMs: Long = 0L,
) : Head {
    override val label = key
    override val port = 0
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = running, running = running, port = 0, version = "kt-1")
    override fun providerResetForMs(): Long = refusedForMs
}

class OutOfQuotaHeadsRouteTest {
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
        usageWarning = UsageWarning(warnPct = 80, warnTokens5h = 0),
        authSurface = HeadAuthSurface(authKind = "x"),
    )

    private val heads = listOf(
        ProviderHead("claudex", refusedForMs = THREE_DAYS_MS),
        ProviderHead("grok"),
        ProviderHead("muse", running = false, refusedForMs = THREE_DAYS_MS),
    ).associate { it.key to managed(it) }

    private val signals = signalsFor(heads)
    private val payloads = healthFor(heads, signals = signals)

    private fun statuses(): Map<String, JsonObject> = HeadResolver(heads, signals).headStatuses(NOW_MS)
        .associateBy { it.getValue("key").jsonPrimitive.content }

    @Test
    fun `the heads route carries the instant the provider refuses until, for that head alone`() {
        val byKey = statuses()
        assertEquals(
            (NOW_MS + THREE_DAYS_MS) / 1000,
            byKey.getValue("claudex").getValue(FIELD).jsonPrimitive.long,
        )
        assertNull(byKey.getValue("grok")[FIELD], "a head the provider is not refusing carries no instant")
    }

    @Test
    fun `a head that is not running carries none, as health does`() {
        assertNull(statuses().getValue("muse")[FIELD])
    }

    @Test
    fun `the instant is the one health carries, and health's counts are what they were`() {
        val health = Json.parseToJsonElement(payloads.json(NOW_MS)).jsonObject
        assertEquals(
            statuses().getValue("claudex").getValue(FIELD).jsonPrimitive.long,
            health.getValue(FIELD).jsonObject.getValue("claudex").jsonPrimitive.long,
        )
        assertEquals(2, health.getValue("readyHeads").jsonPrimitive.int)
        assertEquals(0, health.getValue("failedHeads").jsonPrimitive.int)
    }
}

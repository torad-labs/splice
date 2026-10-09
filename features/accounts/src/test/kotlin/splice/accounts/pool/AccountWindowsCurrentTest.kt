// /api/accounts says which windows are current, by the rule /api/usage applies.
// Marlin's walk on bb54736ea: a codex 7d 100% reading 4.5 h old, on a home with no ChatGPT sign-in,
// was the console's nearest limit, because account rows carried every window with no age rule.
// The figures still ship; only the current flags decide what the nearest limit may rank.
package splice.accounts.pool

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.HeadQuotaSource
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView

class AccountWindowsCurrentTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a pooled reading four and a half hours old is not current and keeps its figures`() = runBlocking {
        val row = pooledRow(observedAt = NOW - 16_200L, fiveHourReset = NOW + 3_600L, sevenDayReset = NOW + 6 * DAY)

        assertEquals(false, row.flag("five_hour_current"))
        assertEquals(false, row.flag("seven_day_current"))
        val shown = row["seven_day_used_percent"]!!.jsonPrimitive.content.toDouble()
        assertEquals(100.0, shown, "the Accounts page still shows it")
    }

    @Test
    fun `a pooled reading a minute old is current`() = runBlocking {
        val row = pooledRow(observedAt = NOW - 60L, fiveHourReset = NOW + 3_600L, sevenDayReset = NOW + 6 * DAY)

        assertEquals(true, row.flag("five_hour_current"))
        assertEquals(true, row.flag("seven_day_current"))
    }

    @Test
    fun `a window whose reset has passed is not current however fresh the reading`() = runBlocking {
        val row = pooledRow(observedAt = NOW - 60L, fiveHourReset = NOW - 1L, sevenDayReset = NOW + 6 * DAY)

        assertEquals(false, row.flag("five_hour_current"))
        assertEquals(true, row.flag("seven_day_current"))
    }

    @Test
    fun `a single login follows the same rule`() = runBlocking {
        val stale = singleLoginRow(observedAt = NOW - 16_200L)
        val fresh = singleLoginRow(observedAt = NOW - 60L)

        assertEquals(false, stale.flag("seven_day_current"))
        assertEquals(true, fresh.flag("seven_day_current"))
    }

    @Test
    fun `a window with no figure is not current`() = runBlocking {
        val row = singleLoginRow(observedAt = null)

        assertEquals(false, row.flag("five_hour_current"))
        assertEquals(false, row.flag("seven_day_current"))
    }

    private suspend fun pooledRow(observedAt: Long, fiveHourReset: Long, sevenDayReset: Long): JsonObject {
        val account = HeadAccountView(
            "work",
            true,
            true,
            true,
            "pro",
            40.0,
            fiveHourReset,
            100.0,
            sevenDayReset,
            fiveHourWindowSeconds = 18_000L,
            sevenDayWindowSeconds = 604_800L,
            quotaObservedAtEpochSeconds = observedAt,
        )
        val pool = HeadAccountPoolView(selectedLabel = "work", accounts = listOf(account), lastSwitch = null)
        val head = base("claudex").copy(
            pool = HeadAccountPoolSource { pool },
            accountAuth = HeadAccountAuthSource {
                mapOf("work" to AuthDescription(true, "chatgpt-oauth", mapOf("auth_path" to "/pool/work.json")))
            },
        )
        return rowOf(head)
    }

    private suspend fun singleLoginRow(observedAt: Long?): JsonObject {
        val quota = observedAt?.let {
            QuotaView(
                fiveHour = QuotaWindowView(usedPct = 20, resetsAt = NOW + 3_600L, observedAt = it),
                sevenDay = QuotaWindowView(usedPct = 100, resetsAt = NOW + 6 * DAY, observedAt = it),
                plan = "pro",
            )
        }
        return rowOf(base("solo").copy(quota = quota?.let { q -> HeadQuotaSource { q } }))
    }

    private suspend fun rowOf(head: AccountHead): JsonObject {
        val body = json.parseToJsonElement(AccountsRoute(mapOf(head.key to head)).accountsJson(NOW)).jsonObject
        return body["accounts"]!!.jsonArray.single().jsonObject
    }

    private fun JsonObject.flag(name: String): Boolean? = this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

    private fun base(key: String): AccountHead = AccountHead(
        key = key,
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
        },
        restart = HeadRestart {},
    )

    private companion object {
        const val NOW = 1_790_630_000L
        const val DAY = 86_400L
    }
}

// NEW: V4-132 — GET /api/accounts (FEATURES.md §6, §4.5 "All accounts, one screen"). Direct unit
// tests of the join, one level below the HTTP rig AuthAndAccountsRoutesTest uses for the routes
// around it: two heads sharing a credential path fold into one row with both head keys, an
// api-key head contributes nothing (a different feature row), and a head with no pool at all still
// appears, `single_login: true`, from its /api/auth view alone.
package splice.accounts.pool

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.HeadQuotaSource
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView

class AccountsRouteTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `two heads sharing one credential path join into one row with both head keys`() = runBlocking {
        val poolAccount = HeadAccountView(
            "backup",
            false,
            true,
            true,
            "plus",
            10.0,
            100L,
            5.0,
            200L,
            fiveHourWindowSeconds = 18_000L,
            sevenDayWindowSeconds = 604_800L,
            quotaObservedAtEpochSeconds = 1_699_999_000L,
        )
        val pool = HeadAccountPoolView(selectedLabel = "backup", accounts = listOf(poolAccount), lastSwitch = null)
        val authPath = "/shared/backup.json"
        val heads = mapOf(
            "head-a" to oauthHead("head-a", pool, authPath),
            "head-b" to oauthHead("head-b", pool, authPath),
        )

        val body = json.parseToJsonElement(AccountsRoute(heads).accountsJson()).jsonObject
        val accounts = body["accounts"]!!.jsonArray
        assertEquals(1, accounts.size, "one credential path must be one row, not one per head")
        val row = accounts.single().jsonObject
        assertEquals(authPath, row["credential_path"]!!.jsonPrimitive.content)
        val riders = row["heads"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("head-a", "head-b"), riders)
        assertEquals(18_000L, row["five_hour_window_seconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(604_800L, row["seven_day_window_seconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_699_999_000L, row["observed_at_epoch_seconds"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `an api-key head contributes no rows — a different feature row entirely`() = runBlocking {
        val heads = mapOf("keyed" to apiKeyHead("keyed"))

        val body = json.parseToJsonElement(AccountsRoute(heads).accountsJson()).jsonObject

        assertTrue(body["accounts"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a head with no pool still appears, single_login true, read from its auth view`() = runBlocking {
        val heads = mapOf("solo" to oauthHeadNoPool("solo"))

        val body = json.parseToJsonElement(AccountsRoute(heads).accountsJson()).jsonObject
        val row = body["accounts"]!!.jsonArray.single().jsonObject

        assertEquals("true", row["single_login"]!!.jsonPrimitive.content)
        assertEquals("true", row["primary"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, row["label"])
        assertEquals(listOf("solo"), row["heads"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    // console live evidence, 2026-09-24: claudex/claude-grok/claude-kimi/claude-muse all read
    // five_hour_*/seven_day_*/plan null on /api/accounts while /api/usage carried real numbers for
    // the same heads — foldSingleLogin hard-coded the windows instead of reading the head's quota.
    @Test
    fun `a single-login head with a quota snapshot carries its plan, windows and observed_at`() = runBlocking {
        val quota = QuotaView(
            fiveHour = QuotaWindowView(usedPct = 42, resetsAt = 1_700_000_000L, observedAt = 1_699_999_000L),
            sevenDay = QuotaWindowView(usedPct = 50, resetsAt = 1_700_500_000L, observedAt = 1_699_999_000L),
            plan = "pro",
        )
        val heads = mapOf("solo" to oauthHeadNoPool("solo", quota))

        val body = json.parseToJsonElement(AccountsRoute(heads).accountsJson()).jsonObject
        val row = body["accounts"]!!.jsonArray.single().jsonObject

        assertEquals("pro", row["plan"]!!.jsonPrimitive.content)
        assertEquals(42.0, row["five_hour_used_percent"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1_700_000_000L, row["five_hour_reset_epoch_seconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(50.0, row["seven_day_used_percent"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1_700_500_000L, row["seven_day_reset_epoch_seconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_699_999_000L, row["observed_at_epoch_seconds"]!!.jsonPrimitive.content.toLong())
        // The head's own tracked QuotaView carries no window LENGTH (only a pooled AccountView does).
        assertEquals(JsonNull, row["five_hour_window_seconds"])
        assertEquals(JsonNull, row["seven_day_window_seconds"])
    }

    @Test
    fun `a single-login head with no quota snapshot keeps every window and plan null`() = runBlocking {
        val heads = mapOf("solo" to oauthHeadNoPool("solo"))

        val body = json.parseToJsonElement(AccountsRoute(heads).accountsJson()).jsonObject
        val row = body["accounts"]!!.jsonArray.single().jsonObject

        assertEquals(JsonNull, row["plan"])
        assertEquals(JsonNull, row["five_hour_used_percent"])
        assertEquals(JsonNull, row["seven_day_used_percent"])
        assertEquals(JsonNull, row["observed_at_epoch_seconds"])
    }

    private fun oauthHead(key: String, pool: HeadAccountPoolView, authPath: String): AccountHead =
        base(key, "chatgpt-oauth").copy(
            pool = HeadAccountPoolSource { pool },
            accountAuth = HeadAccountAuthSource {
                mapOf("backup" to AuthDescription(true, "chatgpt-oauth", mapOf("auth_path" to authPath)))
            },
        )

    private fun oauthHeadNoPool(key: String, quota: QuotaView? = null): AccountHead =
        base(key, "chatgpt-oauth").copy(quota = quota?.let { q -> HeadQuotaSource { q } })

    private fun apiKeyHead(key: String): AccountHead = base(key, "api-key")

    private fun base(key: String, authKind: String): AccountHead = AccountHead(
        key = key,
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, authKind, emptyMap())
        },
        restart = HeadRestart {},
    )
}

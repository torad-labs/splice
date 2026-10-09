// NEW: both native commands appear with independent facts while OAuth and native pool semantics remain separate.
package splice.accounts.claude

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.pool.AccountsRoute
import splice.accounts.pool.HeadAccountAuthSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountQuota
import splice.accounts.pool.HeadAccountView
import splice.accounts.pool.HeadAccountWindow
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow

class ClaudeAccountRowsTest {
    private fun forwardedAuth(): AuthProvider = object : AuthProvider {
        override suspend fun credentials(): Credentials = Credentials.ClientForwarded
        override suspend fun describe(): AuthDescription = AuthDescription(true, "client")
    }

    private fun storedDescriptions(): Map<String, AuthDescription> = mapOf(
        "work" to AuthDescription(
            true,
            "claude-account",
            mapOf(
                "auth_path" to "/synthetic/work/.credentials.json",
                "account_uuid" to "work-account",
                "account_email" to "work@synthetic.test",
            ),
        ),
    )

    @Test
    fun `native rows distinguish a verified profile from an unresolved profile`() = runBlocking {
        val pending = ClaudeLoginPlaceView(
            ClaudeLoginPlaceId.NATIVE,
            "synthetic-client",
            ClaudeLoginCredential("/synthetic/login", true),
            ClaudeLoginIdentity(null),
            null,
            ClaudeLoginStanding(null, null),
        )
        val verified = pending.copy(
            identity = ClaudeLoginIdentity(
                ClaudeAccountIdentity("synthetic-account", null),
                ClaudeProfileState.VERIFIED,
            ),
        )
        val refused = pending.copy(identity = ClaudeLoginIdentity(null, ClaudeProfileState.REFUSED))
        for ((view, state) in listOf(pending to "pending", verified to "verified", refused to "refused")) {
            val row = ClaudeLoginRows.json(view, "synthetic-provider", null, 100)
            assertEquals(state, row.getValue("profile_state").jsonPrimitive.content)
            assertFalse(row.toString().contains("credential_key"))
            assertFalse(row.toString().contains("accessToken"))
        }
    }

    @Test
    fun `a client kind head exposes its real added pool login beside the native place`() = runBlocking {
        val native = ClaudeLoginPlaceView(
            ClaudeLoginPlaceId.NATIVE,
            "claude-splice",
            ClaudeLoginCredential("/synthetic/native/.credentials.json", true),
            ClaudeLoginIdentity(ClaudeAccountIdentity("native-account", "native@synthetic.test")),
            null,
            ClaudeLoginStanding(null, null),
        )
        val added = HeadAccountView(
            label = "work",
            primary = false,
            selected = true,
            available = true,
            plan = "max",
            quota = HeadAccountQuota(
                fiveHour = HeadAccountWindow(usedPercent = 11.0, resetEpochSeconds = 20_000),
                sevenDay = HeadAccountWindow(usedPercent = 77.0, resetEpochSeconds = 30_000),
            ),
        )
        val head = AccountHead(
            key = "claude-splice",
            auth = forwardedAuth(),
            pool = HeadAccountPoolSource { HeadAccountPoolView("work", listOf(added), null) },
            accountAuth = HeadAccountAuthSource { storedDescriptions() },
            restart = HeadRestart { },
        )
        val body = AccountsRoute(mapOf(head.key to head)).accountsJson(
            mapOf(head.key to "anthropic"),
            listOf(native),
            mapOf(head.key to null),
            nowSeconds = 100,
        )
        val rows = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
        assertEquals(2, rows.size, "a real client pool is not discarded before folding")
        val work = rows.map { it.jsonObject }.single { it["label"]?.jsonPrimitive?.content == "work" }
        val place = rows.map { it.jsonObject }.single { it["label"]?.jsonPrimitive?.content == "claude" }
        assertEquals(JsonNull, place["carrying_request"], "before any request matched, no place is carrying")
        assertFalse(work.containsKey("carrying_request"), "an added pool login keeps its own selection fields")
        assertEquals(11.0, work.getValue("five_hour_used_percent").jsonPrimitive.content.toDouble())
        assertTrue(work["account"] is JsonObject, body)
        assertEquals("work-account", work.getValue("account").jsonObject.getValue("uuid").jsonPrimitive.content)
        assertEquals("pool", work.getValue("edit_target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("work", work.getValue("edit_target").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("true", work.getValue("can_remove").jsonPrimitive.content)
        assertEquals("work", work.getValue("display_name").jsonPrimitive.content)
    }

    @Test
    fun `two commands on one head remain distinct rows and unknown windows never become zero or full limits`() =
        runBlocking {
            val first = ClaudeLoginPlaceView(
                ClaudeLoginPlaceId.NATIVE,
                "claude-splice",
                ClaudeLoginCredential("/synthetic/native/.credentials.json", true),
                ClaudeLoginIdentity(ClaudeAccountIdentity("native-account", "native@test")),
                QuotaSnapshot(QuotaWindow(42.0, 20_000, 18_000), updatedAt = 100_000),
                ClaudeLoginStanding(true, 200),
            )
            val second = first.copy(
                id = ClaudeLoginPlaceId.SPLICE,
                credential = first.credential.copy(path = "/synthetic/separate/.credentials.json"),
                identity = ClaudeLoginIdentity(ClaudeAccountIdentity("separate-account", null)),
                quota = null,
                standing = ClaudeLoginStanding(null, null),
            )
            val body = AccountsRoute(emptyMap()).accountsJson(
                mapOf("claude-splice" to "native-provider"),
                listOf(first, second),
                mapOf("claude-splice" to ClaudeLoginPlaceId.NATIVE),
                nowSeconds = 100,
            )
            val rows = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
            assertEquals(2, rows.size)
            val native = rows[0].jsonObject
            val separate = rows[1].jsonObject
            assertEquals("native-provider", native.getValue("provider").jsonPrimitive.content)
            assertEquals("claude", native.getValue("login_place").jsonObject.getValue("command").jsonPrimitive.content)
            assertEquals(42.0, native.getValue("five_hour_used_percent").jsonPrimitive.content.toDouble())
            assertEquals("100", native.getValue("five_hour_limit_percent").jsonPrimitive.content)
            assertEquals(JsonNull, native["seven_day_limit_percent"])
            assertEquals("true", native.getValue("five_hour_current").jsonPrimitive.content)
            assertEquals(JsonNull, native.getValue("failover_positions").jsonObject["claude-splice"])
            assertEquals(JsonNull, separate["five_hour_used_percent"])
            assertEquals(JsonNull, separate["held"])
            assertEquals("true", native.getValue("carrying_request").jsonPrimitive.content, "the matched place is")
            assertEquals("false", separate.getValue("carrying_request").jsonPrimitive.content, "the sibling is not")
            assertTrue(body.contains("separate-account"))
            assertFalse(body.contains("used_usd"))
            assertFalse(body.contains("credential_key"))
        }
}

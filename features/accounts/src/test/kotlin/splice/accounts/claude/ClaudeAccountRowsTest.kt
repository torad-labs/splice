// NEW: both native commands appear with independent facts while OAuth and native pool semantics remain separate.
package splice.accounts.claude

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.pool.AccountsRoute
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow

class ClaudeAccountRowsTest {
    @Test
    fun `two commands on one head remain distinct rows and unknown windows never become zero or full limits`() =
        runBlocking {
            val first = ClaudeLoginPlaceView(
                ClaudeLoginPlaceId.NATIVE,
                "claude-splice",
                "/synthetic/native/.credentials.json",
                true,
                ClaudeAccountIdentity("native-account", "native@test"),
                QuotaSnapshot(QuotaWindow(42.0, 20_000, 18_000), updatedAt = 100_000),
                ClaudeLoginStanding(true, 200),
            )
            val second = first.copy(
                id = ClaudeLoginPlaceId.SPLICE,
                credentialPath = "/synthetic/separate/.credentials.json",
                account = ClaudeAccountIdentity("separate-account", null),
                quota = null,
                standing = ClaudeLoginStanding(null, null),
            )
            val body = AccountsRoute(emptyMap()).accountsJson(
                mapOf("claude-splice" to "native-provider"),
                listOf(first, second),
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
            assertTrue(body.contains("separate-account"))
            assertFalse(body.contains("used_usd"))
            assertFalse(body.contains("credential_key"))
        }
}

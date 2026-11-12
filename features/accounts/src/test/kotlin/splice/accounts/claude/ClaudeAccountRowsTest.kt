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
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials

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
                ACCOUNT_REF_FIELD to AccountRef.of("work-account"),
                "email" to "work@synthetic.test",
            ),
        ),
    )

    @Test
    fun `a login place no credential has proved reads Account, never its command's word`() = runBlocking {
        val unproved = ClaudeLoginPlaceView(
            ClaudeLoginPlaceId.NATIVE,
            "claude-splice",
            ClaudeLoginCredential("/synthetic/native/.credentials.json", true),
            ClaudeLoginIdentity(null),
            null,
            ClaudeLoginStanding(null, null),
        )
        val row = ClaudeLoginRows().json(unproved, "anthropic", null, 100)
        assertEquals("Account", row.getValue("display_name").jsonPrimitive.content)
        assertEquals("true", row.getValue("label_default").jsonPrimitive.content)
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
        assertClientPoolRows(body)
    }

    private fun assertClientPoolRows(body: String) {
        val rows = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
        assertEquals(2, rows.size, "a real client pool is not discarded before folding")
        val work = rows.map { it.jsonObject }.single { it["label"]?.jsonPrimitive?.content == "work" }
        val place = rows.map { it.jsonObject }.single { it["label"]?.jsonPrimitive?.content == "claude" }
        assertEquals(JsonNull, place["carrying_request"], "before any request matched, no place is carrying")
        assertFalse(work.containsKey("carrying_request"), "an added pool login keeps its own selection fields")
        assertEquals(11.0, work.getValue("five_hour_used_percent").jsonPrimitive.content.toDouble())
        // The added row's chosen label beats its email; the native login uses its proved email as the default.
        assertFalse(work.containsKey("account"), body)
        assertEquals(AccountRef.of("work-account"), work.getValue("ref").jsonPrimitive.content)
        assertEquals(AccountRef.of("native-account"), place.getValue("ref").jsonPrimitive.content)
        assertEquals("native@synthetic.test", place.getValue("display_name").jsonPrimitive.content)
        assertEquals("work", work.getValue("display_name").jsonPrimitive.content)
        assertFalse("native-account" in body || "work-account" in body, body)
        assertEquals("pool", work.getValue("edit_target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("work", work.getValue("edit_target").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("true", work.getValue("can_remove").jsonPrimitive.content)
    }

    @Test
    fun `a pinned native login names its head in pinned_heads, so the console shows its Unpin`() = runBlocking {
        val native = ClaudeLoginPlaceView(
            ClaudeLoginPlaceId.NATIVE,
            "claude-splice",
            ClaudeLoginCredential("/synthetic/native/.credentials.json", true),
            ClaudeLoginIdentity(ClaudeAccountIdentity("native-account", "native@synthetic.test")),
            null,
            ClaudeLoginStanding(null, null),
        )
        val key = "native:${ClaudeLoginPlaceId.NATIVE.wire}"
        suspend fun rowWith(pinned: String?): JsonObject {
            val head = AccountHead(
                key = "claude-splice",
                auth = forwardedAuth(),
                pool = HeadAccountPoolSource { HeadAccountPoolView(null, emptyList(), null, pinnedLabel = pinned) },
                accountAuth = HeadAccountAuthSource { emptyMap() },
                restart = HeadRestart { },
            )
            val body = AccountsRoute(mapOf(head.key to head)).accountsJson(
                mapOf(head.key to "anthropic"),
                listOf(native),
                mapOf(head.key to null),
                nowSeconds = 100,
            )
            return Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
                .map { it.jsonObject }.single { it["label"]?.jsonPrimitive?.content == "claude" }
        }

        val pinned = rowWith(key)
        val heads = pinned.getValue("pinned_heads").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("claude-splice"), heads)
        val free = rowWith(null)
        assertTrue(free.getValue("pinned_heads").jsonArray.isEmpty(), "nothing pinned, no head named")
    }
}

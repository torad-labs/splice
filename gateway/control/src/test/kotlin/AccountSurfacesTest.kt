import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.HeadAccountPoolSource
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountSwitchView
import splice.control.HeadAccountView
import splice.control.StatuslineRenderer
import splice.control.api.AccountPoolJson
import splice.core.auth.AuthDescription

class AccountSurfacesTest {
    @Test
    fun `auth projection lists accounts and excludes credential material`() {
        val view = poolView()
        val body = buildJsonObject {
            AccountPoolJson().write(
                this,
                view,
                mapOf(
                    "backup" to AuthDescription(
                        present = true,
                        kind = "chatgpt-oauth",
                        fields = mapOf(
                            "account_id_masked" to "acct…5678",
                            "token" to "raw-secret-token",
                            "email" to "private@example.com",
                            "auth_path" to "/private/auth.json",
                            "extra_headers" to "secret-header",
                        ),
                    ),
                ),
            )
        }.toString()

        val pool = Json.parseToJsonElement(body).jsonObject["account_pool"]!!.jsonObject
        val backup = pool["accounts"]!!.jsonArray.last().jsonObject
        assertEquals("backup", pool["selected_label"]?.jsonPrimitive?.content)
        assertEquals("acct…5678", backup["auth"]?.jsonObject?.get("account_id_masked")?.jsonPrimitive?.content)
        assertEquals("5-hour quota exhausted", pool["last_switch"]?.jsonObject?.get("reason")?.jsonPrimitive?.content)
        assertFalse(body.contains("raw-secret-token"))
        assertFalse(body.contains("private@example.com"))
        assertFalse(body.contains("/private/auth.json"))
        assertFalse(body.contains("secret-header"))
    }

    @Test
    fun `auth projection reports missing primary credentials separately from quota availability`() {
        val view = poolView().let { pool ->
            pool.copy(accounts = pool.accounts.map { it.copy(credentialPresent = !it.primary) })
        }
        val payload = buildJsonObject { AccountPoolJson().write(this, view) }
        val accounts = payload["account_pool"]!!.jsonObject["accounts"]!!.jsonArray

        assertEquals("false", accounts.first().jsonObject["credential_present"]?.jsonPrimitive?.content)
        assertEquals("true", accounts.last().jsonObject["credential_present"]?.jsonPrimitive?.content)
    }

    @Test
    fun `auth projection reports auth exclusion separately from generic availability`() {
        val held = poolView().let { pool ->
            pool.copy(
                accounts = pool.accounts.map { account ->
                    if (account.primary) {
                        account.copy(
                            available = false,
                            authExcludedUntilEpochMillis = 1_700_000_300_000L,
                            authExclusionReason = "terminal_401",
                        )
                    } else {
                        account.copy(available = false)
                    }
                },
            )
        }
        val payload = buildJsonObject { AccountPoolJson().write(this, held) }
        val accounts = payload["account_pool"]!!.jsonObject["accounts"]!!.jsonArray
        val primary = accounts.first().jsonObject
        val rateLimited = accounts.last().jsonObject

        assertEquals("1700000300000", primary["auth_excluded_until_epoch_millis"]?.jsonPrimitive?.content)
        assertEquals("terminal_401", primary["auth_exclusion_reason"]?.jsonPrimitive?.content)
        assertTrue("auth_excluded_until_epoch_millis" in rateLimited)
        assertTrue("auth_exclusion_reason" in rateLimited)
        assertEquals("null", rateLimited["auth_excluded_until_epoch_millis"].toString())
        assertEquals("null", rateLimited["auth_exclusion_reason"].toString())
    }

    @Test
    fun `statusline names the selected account and uses its quota for the session`() {
        var seenSession: String? = null
        val source = HeadAccountPoolSource { sessionId ->
            seenSession = sessionId
            poolView()
        }
        val renderer = StatuslineRenderer(label = "Codex", accountPool = source)
        val stdin = """{
            "session_id":"session-7",
            "model":{"display_name":"Codex"},
            "rate_limits":{"five_hour":{"used_percentage":1,"resets_at":100}}
        }"""

        val line = renderer.render(stdin, usage = null, warnPct = 80, warnTokens5h = 0, sessionId = "session-7")

        assertEquals("session-7", seenSession)
        assertTrue(line.contains("backup"), line)
        assertTrue(line.contains("5-hour quota exhausted"), line)
        assertTrue(line.contains("87%"), line)
        assertFalse(line.contains(" 1%"), line)
    }

    @Test
    fun `an account without a snapshot yet keeps the client rate_limits bars instead of empty ones`() {
        val fresh = HeadAccountPoolView(
            selectedLabel = "backup",
            accounts = listOf(
                HeadAccountView("primary", true, false, false, "plus", 100.0, 100L, 20.0, 200L),
                HeadAccountView("backup", false, true, true, null, null, null, null, null),
            ),
            lastSwitch = null,
        )
        assertEquals(null, fresh.selectedQuota(), "no windows means no view: /api/usage falls through")
        val renderer = StatuslineRenderer(label = "Codex", accountPool = { fresh })
        val stdin = """{"model":{"display_name":"Codex"},""" +
            """"rate_limits":{"five_hour":{"used_percentage":1,"resets_at":100}}}"""

        val line = renderer.render(stdin, usage = null, warnPct = 80, warnTokens5h = 0, sessionId = "s")

        assertTrue(line.contains("backup"), line)
        assertTrue(line.contains("1%"), line)
        assertFalse(line.contains("100%"), "the primary's exhausted window is not the selected account's: $line")
    }

    @Test
    fun `an unknown selection names no account on any surface, never the primary`() {
        val unknown = poolView().copy(selectionUnknown = true)
        assertNull(unknown.selectedAccount(), "a rejected selection must not fall back to the primary")
        assertNull(unknown.selectedQuota(), "and lends no windows to the status line")
        assertEquals("backup", poolView().selectedAccount()?.label, "control: a known selection is named")
    }

    private fun poolView(): HeadAccountPoolView = HeadAccountPoolView(
        selectedLabel = "backup",
        accounts = listOf(
            HeadAccountView("primary", true, false, false, "plus", 100.0, 100L, 20.0, 200L),
            HeadAccountView("backup", false, true, true, "plus", 87.0, 300L, 42.0, 400L),
        ),
        lastSwitch = HeadAccountSwitchView(
            from = "primary",
            to = "backup",
            reason = "5-hour quota exhausted",
            atEpochMillis = 1234L,
        ),
    )
}

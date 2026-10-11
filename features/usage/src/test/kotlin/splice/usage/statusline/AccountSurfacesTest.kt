package splice.usage.statusline

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
import splice.accounts.pool.AccountPoolJson
import splice.accounts.pool.HeadAccountCredential
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountQuota
import splice.accounts.pool.HeadAccountSwitchView
import splice.accounts.pool.HeadAccountView
import splice.accounts.pool.HeadAccountWindow
import splice.core.auth.AuthDescription
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.core.util.WallClock
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

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
            pool.copy(accounts = pool.accounts.map { it.copy(credential = it.credential.copy(present = !it.primary)) })
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
                            credential = HeadAccountCredential(
                                excludedUntilEpochMillis = 1_700_000_300_000L,
                                exclusionReason = "terminal_401",
                            ),
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
        // V4-327: the selected account's windows draw only while current, so this pool was read a
        // minute before the tick and its windows reset later.
        val current = poolView().let { pool ->
            pool.copy(
                accounts = pool.accounts.map {
                    it.copy(
                        quota = HeadAccountQuota(
                            fiveHour = it.quota.fiveHour.copy(resetEpochSeconds = NOW_S + 3_600L),
                            sevenDay = it.quota.sevenDay.copy(resetEpochSeconds = NOW_S + 86_400L),
                            observedAtEpochSeconds = NOW_S - 60L,
                        ),
                    )
                },
            )
        }
        val source = HeadAccountPoolSource { sessionId ->
            seenSession = sessionId
            current
        }
        val renderer = StatuslineRenderer(label = "Codex", now = WallClock { NOW_S * 1_000L }, accountPool = source)
        val stdin = """{
            "session_id":"session-7",
            "model":{"display_name":"Codex"},
            "rate_limits":{"five_hour":{"used_percentage":1,"resets_at":${NOW_S + 3_600L}}}
        }"""

        val line = renderer.render(stdin, usage = null, StatuslineWarn(80, 0), sessionId = "session-7")

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
                HeadAccountView(
                    "primary",
                    true,
                    false,
                    false,
                    "plus",
                    HeadAccountQuota(HeadAccountWindow(100.0, 100L), HeadAccountWindow(20.0, 200L)),
                ),
                HeadAccountView("backup", false, true, true, null),
            ),
            lastSwitch = null,
        )
        assertEquals(null, fresh.selectedQuota(), "no windows means no view: /api/usage falls through")
        val renderer = StatuslineRenderer(label = "Codex", now = WallClock { NOW_S * 1_000L }, accountPool = { fresh })
        val stdin = """{"model":{"display_name":"Codex"},""" +
            """"rate_limits":{"five_hour":{"used_percentage":1,"resets_at":${NOW_S + 3_600L}}}}"""

        val line = renderer.render(stdin, usage = null, StatuslineWarn(80, 0), sessionId = "s")

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

    // Review of adf35c39e, finding 3. The head's own tracked quota is the PRIMARY's, so it can stand in for
    // the selected account only while the primary is the one in view. Drawn under another account's name it
    // was the primary's bars on the wrong account.
    @Test
    fun `an account with no reading shows no bars, never the head's tracked ones under its name`() {
        val primaryBars = QuotaView(QuotaWindowView(100, NOW_S + 3_600L, NOW_S), null, "plus")
        val fresh = HeadAccountPoolView(
            selectedLabel = "work",
            accounts = listOf(
                HeadAccountView("primary", true, false, true, "plus"),
                HeadAccountView("work", false, true, true, "pro"),
            ),
            lastSwitch = null,
        )
        val renderer = StatuslineRenderer(label = "Codex", now = WallClock { NOW_S * 1_000L }, accountPool = { fresh })
        val usage = HeadUsageSource { UsageView(0L, 0, null, primaryBars) }

        val line = renderer.render("""{"model":{"display_name":"Codex"}}""", usage, StatuslineWarn(80, 0), "s")

        assertTrue(line.contains("work"), line)
        assertFalse(line.contains("100%"), "the primary's bars are not the work account's: $line")
        assertNull(fresh.quotaOr(primaryBars), "an account with no reading yet shows none")
    }

    @Test
    fun `the head's tracked quota still stands in while the primary is the account in view`() {
        val primaryBars = QuotaView(QuotaWindowView(61, NOW_S + 3_600L, NOW_S), null, "plus")
        val onPrimary = HeadAccountPoolView(
            selectedLabel = "primary",
            accounts = listOf(
                HeadAccountView("primary", true, true, true, "plus"),
                HeadAccountView("work", false, false, true, "pro"),
            ),
            lastSwitch = null,
        )

        assertEquals(primaryBars, onPrimary.quotaOr(primaryBars))
    }

    @Test
    fun `a provider's answer of no usage is kept as that answer and not replaced by the head's bars`() {
        val answered = HeadAccountPoolView(
            selectedLabel = "work",
            accounts = listOf(
                HeadAccountView("primary", true, false, true, "plus"),
                HeadAccountView(
                    "work",
                    false,
                    true,
                    true,
                    "pro",
                    HeadAccountQuota(noUsageAtEpochSeconds = NOW_S),
                ),
            ),
            lastSwitch = null,
        )

        val shown = answered.selectedQuota()

        assertEquals(NOW_S, shown?.noUsageAt, "Accounts keeps its no-usage marker for the selected account")
        assertNull(shown?.fiveHour)
        assertNull(shown?.sevenDay)
    }

    private fun poolView(): HeadAccountPoolView = HeadAccountPoolView(
        selectedLabel = "backup",
        accounts = listOf(
            HeadAccountView(
                "primary",
                true,
                false,
                false,
                "plus",
                HeadAccountQuota(HeadAccountWindow(100.0, 100L), HeadAccountWindow(20.0, 200L)),
            ),
            HeadAccountView(
                "backup",
                false,
                true,
                true,
                "plus",
                HeadAccountQuota(HeadAccountWindow(87.0, 300L), HeadAccountWindow(42.0, 400L)),
            ),
        ),
        lastSwitch = HeadAccountSwitchView(
            from = "primary",
            to = "backup",
            reason = "5-hour quota exhausted",
            atEpochMillis = 1234L,
        ),
    )
}

/** A fixed tick for the status-line cases (V4-327 draws a window only while current). */
private const val NOW_S = 1_790_449_350L

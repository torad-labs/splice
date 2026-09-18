// v0.4.0 (FEATURES.md §11): what `splice status` and `splice doctor` say about a head's accounts,
// parsed from the daemon's /api/auth projection — labels, windows and the last switch, nothing else.
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.AccountPoolProjection
import splice.app.cli.AccountPoolText
import splice.app.cli.AccountSwitchReasonText
import splice.app.cli.CheckStatus
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountSwitchView
import splice.control.HeadAccountView
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.spi.AccountNow
import splice.spi.AccountPool
import splice.spi.Selection
import splice.spi.AccountQuotaSource
import splice.spi.ElapsedNow
import splice.spi.PoolAccount
import splice.spi.RateLimitCooldown
import java.time.Instant

class AccountStatusTest {

    private val body = """
        {"claudex":{"kind":"chatgpt-oauth","login":"automated","present":true,
          "account_pool":{"selected_label":"work","accounts":[
            {"label":"primary","primary":true,"selected":false,"available":false,"plan":"plus",
             "auth_excluded_until_epoch_millis":1700000300000,"auth_exclusion_reason":"terminal_401",
             "five_hour_used_percent":100.0,"five_hour_reset_epoch_seconds":1800000000,
             "seven_day_used_percent":61.5,"seven_day_reset_epoch_seconds":1800100000,
             "auth":{"kind":"chatgpt-oauth","present":true,"account_id":"acct-SECRET","email":"ops@example.com"}},
            {"label":"work","primary":false,"selected":true,"available":true,"plan":"pro",
             "five_hour_used_percent":12.4,"five_hour_reset_epoch_seconds":null,
             "seven_day_used_percent":40.0,"seven_day_reset_epoch_seconds":null}],
          "last_switch":{"from":"primary","to":"work","reason":"7d window exhausted","at_epoch_millis":1000000}}},
         "grok":{"kind":"grok-oauth","login":"automated","present":true}}
    """.trimIndent()

    @Test
    fun `the projection keeps labels, windows and the switch and drops everything else`() {
        val pools = AccountPoolProjection().parse(body)
        assertEquals(setOf("claudex"), pools.keys)
        val view = pools.getValue("claudex")
        assertEquals("work", view.selectedAccount()?.label)
        assertEquals(listOf("primary", "work"), view.accounts.map { it.label })
        assertEquals(61.5, view.accounts[0].sevenDayUsedPercent)
        assertEquals(1800000000L, view.accounts[0].fiveHourResetEpochSeconds)
        assertEquals(1_700_000_300_000L, view.accounts[0].authExcludedUntilEpochMillis)
        assertEquals("terminal_401", view.accounts[0].authExclusionReason)
        assertEquals(null, view.accounts[1].authExcludedUntilEpochMillis)
        assertEquals(null, view.accounts[1].authExclusionReason)
        assertEquals("7d window exhausted", view.lastSwitch?.reason)
        assertTrue(view.accounts.all { it.credentialPresent }, "older payloads preserve legacy presence")
        assertFalse(view.toString().contains("SECRET"))
        assertFalse(view.toString().contains("example.com"))
    }

    @Test
    fun `status names the account, the open count, the windows and the last switch`() {
        val view = AccountPoolProjection().parse(body).getValue("claudex")
        val text = AccountPoolText { 1_000_000L + 3 * 60_000L }
        assertEquals(
            "on work (1 of 2 open) · 5h 12% · 7d 40%; switched primary -> work 3m ago: 7d window exhausted",
            text.summary(view),
        )
        assertEquals(CheckStatus.OK, text.check("claudex", view).status)
    }

    @Test
    fun `every account out is a warning naming the earliest reset and the login fix`() {
        val view = HeadAccountPoolView(
            "primary",
            listOf(
                HeadAccountView("primary", true, true, false, "plus", 100.0, 1800000000L, 99.0, 1800100000L),
                HeadAccountView("work", false, false, false, null, null, null, 100.0, 1799999000L),
            ),
            null,
        )
        val check = AccountPoolText { 0 }.check("claudex", view)
        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains(Instant.ofEpochSecond(1799999000L).toString()), check.detail)
        assertTrue(check.detail.contains("on primary (0 of 2 open)"), check.detail)
        assertEquals("splice login claudex --label <name>", check.fix)
    }

    @Test
    fun `missing primary warns with unlabeled login whether the backup is open or exhausted`() {
        for (backupOpen in listOf(true, false)) {
            val view = HeadAccountPoolView(
                "work",
                listOf(
                    HeadAccountView(
                        "primary", true, false, false, null, null, null, null, null, credentialPresent = false,
                    ),
                    HeadAccountView("work", false, true, backupOpen, null, null, null, null, null),
                ),
                null,
            )
            val text = AccountPoolText { 0 }
            val check = text.check("claudex", view)

            assertTrue(text.summary(view).contains("primary credential missing"))
            assertEquals(CheckStatus.WARN, check.status)
            assertTrue(check.detail.contains("primary credential missing"), check.detail)
            assertEquals("splice login claudex", check.fix)
        }
    }

    @Test
    fun `projection drops unknown auth exclusion reasons with their orphaned horizon`() {
        val payload = """{"head":{"account_pool":{"accounts":[{"label":"primary",
            "auth_excluded_until_epoch_millis":1700000300000,
            "auth_exclusion_reason":"private provider body"}]}}}"""

        val account = AccountPoolProjection().parse(payload).getValue("head").accounts.single()

        assertEquals(null, account.authExcludedUntilEpochMillis)
        assertEquals(null, account.authExclusionReason)
        assertFalse(account.toString().contains("private provider body"))
    }

    @Test
    fun `credential presence prefers the new field then masked auth then legacy default`() {
        val cases = listOf(
            """, "credential_present":false, "auth":{"present":true}""" to false,
            """, "credential_present":true, "auth":{"present":false}""" to true,
            """, "auth":{"present":false}""" to false,
            """, "auth":{"present":true}""" to true,
            "" to true,
        )
        for ((fields, expected) in cases) {
            val payload = """{"head":{"account_pool":{"accounts":[{"label":"primary"$fields}]}}}"""
            val view = AccountPoolProjection().parse(payload).getValue("head")
            assertEquals(expected, view.accounts.single().credentialPresent)
        }
    }
}

class AccountLabelBoundaryTest {
    private val unsafeLabels = listOf(
        "ops@example.com",
        "account id",
        "${0x1b.toChar()}[31msecret",
        "UPPER",
        "a".repeat(49),
        "../private",
        "",
    )

    @Test
    fun `projection drops unsafe account and selected labels and either unsafe switch endpoint`() {
        val text = AccountPoolText { 0 }
        for (unsafe in unsafeLabels) {
            val encoded = JsonPrimitive(unsafe).toString()
            val payload = """{"head":{"account_pool":{"selected_label":$encoded,
                "accounts":[{"label":$encoded},{"label":"primary","primary":true,"available":true,
                "five_hour_used_percent":12,"seven_day_used_percent":34}]}}}"""
            val view = AccountPoolProjection().parse(payload).getValue("head")
            assertEquals(null, view.selectedLabel)
            assertEquals(listOf("primary"), view.accounts.map { it.label })
            assertTrue(view.selectionUnknown)
            assertEquals("selection unknown (1 of 1 open)", text.summary(view))
            assertEquals(text.summary(view), text.check("head", view).detail)
            if (unsafe.isNotEmpty()) assertFalse(view.toString().contains(unsafe))
            for ((from, to) in listOf(encoded to "\"primary\"", "\"primary\"" to encoded)) {
                val switched = """{"head":{"account_pool":{"accounts":[],
                    "last_switch":{"from":$from,"to":$to,"reason":"account unavailable"}}}}"""
                assertEquals(null, AccountPoolProjection().parse(switched).getValue("head").lastSwitch)
            }
        }
    }

    @Test
    fun `projection accepts labels only as JSON strings`() {
        val payload = """{"head":{"account_pool":{"selected_label":123,
            "accounts":[{"label":123},{"label":true},null,{}],
            "last_switch":{"from":123,"to":"primary"}}}}"""
        val view = AccountPoolProjection().parse(payload).getValue("head")

        assertEquals(null, view.selectedLabel)
        assertTrue(view.accounts.isEmpty())
        assertTrue(view.selectionUnknown)
        assertEquals(null, view.lastSwitch)
    }

    @Test
    fun `renderer revalidates directly constructed account and switch labels`() {
        val text = AccountPoolText { 0 }
        for (unsafe in unsafeLabels.filter { it.isNotEmpty() }) {
            for ((from, to) in listOf(unsafe to "safe", "safe" to unsafe)) {
                val view = HeadAccountPoolView(
                    unsafe,
                    listOf(
                        HeadAccountView(unsafe, true, true, true, null, null, null, null, null),
                        HeadAccountView("safe", false, false, true, null, null, null, null, null),
                    ),
                    HeadAccountSwitchView(from, to, "account unavailable", 0L),
                )
                assertEquals("selection unknown (1 of 1 open)", text.summary(view))
                assertFalse(text.check("head", view).detail.contains(unsafe))
                assertFalse(text.summary(view).contains("switched"))
            }
        }
    }
}

class AccountSwitchReasonBoundaryTest {
    private val text = AccountPoolText { 0 }
    private val account = HeadAccountView("work", false, true, true, null, null, null, null, null)
    private val unsafeReasons = (0..31).map { "account unavailable${it.toChar()}private" } +
        (127..159).map { "account unavailable${it.toChar()}private" } + listOf(
            "${0x1b.toChar()}[31mprivate",
            "${0x1b.toChar()}]0;private${0x7.toChar()}",
            "account unavailable; private-command",
            "ops@example.com",
            "private prose",
            "a".repeat(256),
            "",
        )

    @Test
    fun `decoder drops hostile switch reasons even when both endpoints are safe`() {
        for (reason in unsafeReasons) {
            val view = decoded("\"reason\":${JsonPrimitive(reason)},")
            assertEquals(null, view.lastSwitch)
            assertEquals("on work (1 of 1 open)", text.summary(view))
            assertEquals(text.summary(view), text.check("head", view).detail)
        }
    }

    @Test
    fun `renderer rejects hostile reasons in directly constructed switches with safe endpoints`() {
        for (reason in unsafeReasons) {
            val view = HeadAccountPoolView(
                "work",
                listOf(account),
                HeadAccountSwitchView("primary", "work", reason, 0L),
            )
            assertEquals("on work (1 of 1 open)", text.summary(view))
            assertEquals(text.summary(view), text.check("head", view).detail)
        }
    }

    @Test
    fun `missing and nonstring reasons cannot enter a printable switch`() {
        for (field in listOf("", "\"reason\":null,", "\"reason\":123,", "\"reason\":true,", "\"reason\":{},")) {
            assertEquals(null, decoded(field).lastSwitch)
        }
    }

    @Test
    fun `internal switch reasons and existing projection wording survive both boundaries`() {
        val reasons = listOf(
            "primary account reset",
            "rate limit exceeds turn wait budget",
            "5-hour quota exhausted",
            "7-day quota exhausted",
            "account unavailable",
            "7d window exhausted",
        )
        for (reason in reasons) {
            val switched = HeadAccountSwitchView("primary", "work", reason, 0L)
            val projected = decoded("\"reason\":${JsonPrimitive(reason)},")
            assertEquals(switched, projected.lastSwitch)
            for (view in listOf(projected, HeadAccountPoolView("work", listOf(account), switched))) {
                val expected = "on work (1 of 1 open); switched primary -> work 0s ago: $reason"
                assertEquals(expected, text.summary(view))
                assertEquals(expected, text.check("head", view).detail)
            }
        }
    }

    private fun decoded(reasonField: String): HeadAccountPoolView {
        val payload = """{"head":{"account_pool":{"selected_label":"work","accounts":[
            {"label":"work","selected":true,"available":true}],
            "last_switch":{$reasonField"from":"primary","to":"work","at_epoch_millis":0}}}}"""
        return AccountPoolProjection().parse(payload).getValue("head")
    }
}

class AccountSwitchVocabularyTest {
    @Test
    fun `every reason produced by pool selection is accepted by the CLI boundary`() {
        val reasons = mutableSetOf<String>()
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("test")
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        }
        // AccountPool.switchReason: missing credential, local unavailability, each quota window,
        // and the return to primary after recovery. No expected reason wording is copied here.
        for (block in Block.entries) {
            var now = 1_000_000L
            val quota = QuotaSnapshot(
                fiveHour = QuotaWindow(if (block == Block.FIVE_HOUR) 100.0 else 0.0, 2_000L, 18_000L),
                sevenDay = QuotaWindow(if (block == Block.SEVEN_DAY) 100.0 else 0.0, 2_000L, 604_800L),
            )
            val primary = PoolAccount(
                label = "primary",
                primary = true,
                auth = auth,
                quota = AccountQuotaSource { quota },
                cooldown = RateLimitCooldown(ElapsedNow { now }),
                credentialPresent = block != Block.CREDENTIAL,
            )
            val backup = primary.copy(
                label = "backup",
                primary = false,
                quota = AccountQuotaSource { null },
                cooldown = RateLimitCooldown(ElapsedNow { now }),
                credentialPresent = true,
            )
            if (block == Block.COOLDOWN) primary.cooldown.markUnavailable(30_000L)
            val pool = AccountPool(listOf(primary, backup), AccountNow { now })
            reasons += requireNotNull((pool.select("session") as Selection.Chosen).account.switch).reason
            now = 2_000_001L
            if (block != Block.CREDENTIAL) {
                reasons += requireNotNull((pool.select("session") as Selection.Chosen).account.switch).reason
            }
        }
        assertEquals(5, reasons.size, "all five current switchReason branches must be exercised")
        reasons.forEach { reason ->
            assertTrue(AccountSwitchReasonText.isSafe(reason), "pool produced a reason the CLI drops: $reason")
        }
    }

    private enum class Block { CREDENTIAL, COOLDOWN, FIVE_HOUR, SEVEN_DAY }
}

class AccountHeadBoundaryTest {
    private val unsafeHeads = listOf(
        "ops@example.com",
        "head prose",
        "${0x1b.toChar()}[31mprivate-head",
        "head\nprivate-head",
        "head;private-command",
        "head|private-command",
        "head&&private-command",
        "head$(private-command)",
        "head`private-command`",
        "../private-head",
        "head/private-head",
        "--private-head",
        "",
    )

    @Test
    fun `unsafe API head keys drop the entire pool before text or login remedies`() {
        for (unsafe in unsafeHeads) {
            for (present in listOf(true, false)) {
                val pool = """{"account_pool":{"selected_label":"primary","accounts":[
                    {"label":"primary","primary":true,"available":false,"credential_present":$present}]}}"""
                val payload = """{${JsonPrimitive(unsafe)}:$pool,"claudex":$pool}"""
                val pools = AccountPoolProjection().parse(payload)
                assertEquals(setOf("claudex"), pools.keys)
                val checks = pools.map { (head, view) -> AccountPoolText { 0 }.check(head, view) }
                val fix = if (present) "splice login claudex --label <name>" else "splice login claudex"
                assertEquals(fix, checks.single().fix)
            }
        }
    }

    @Test
    fun `portable head names retain existing case dots underscores and hyphens`() {
        for (head in listOf(
            "claudex",
            "claude",
            "grok",
            "kimi",
            "muse",
            "claude-muse",
            "Head.Prod_2-v1",
            "0",
            "h".repeat(80),
        )) {
            val payload = """{${JsonPrimitive(head)}:{"account_pool":{"accounts":[]}}}"""
            assertEquals(setOf(head), AccountPoolProjection().parse(payload).keys)
        }
    }
}

class AccountSelectionBoundaryTest {
    private val text = AccountPoolText { 0 }
    private val primary = HeadAccountView("primary", true, false, true, "plus", 12.0, null, 34.0, null)
    private val primaryJson = """{"label":"primary","primary":true,"available":true,
        "five_hour_used_percent":12,"seven_day_used_percent":34}"""

    @Test
    fun `missing selected account cannot turn into a primary selection after decoding`() {
        val payload = """{"head":{"account_pool":{"selected_label":"missing","accounts":[$primaryJson]}}}"""
        val view = AccountPoolProjection().parse(payload).getValue("head")
        assertTrue(view.selectionUnknown)
        assertEquals("missing", view.selectedLabel)
        assertUnknown(view)
    }

    @Test
    fun `malformed selected value stays unknown with a valid primary`() {
        for (selected in listOf("123", "true", "{}", "[]")) {
            val payload = """{"head":{"account_pool":{"selected_label":$selected,"accounts":[$primaryJson]}}}"""
            val view = AccountPoolProjection().parse(payload).getValue("head")
            assertTrue(view.selectionUnknown)
            assertEquals(null, view.selectedLabel)
            assertUnknown(view)
        }
    }

    @Test
    fun `dropping a selected account preserves unknown selection with absent or null selected label`() {
        for (selection in listOf("", "\"selected_label\":null,")) {
            for (label in listOf("\"ops@example.com\"", "123", "true", "null", "{}")) {
                val payload = """{"head":{"account_pool":{$selection
                    "accounts":[$primaryJson,{"label":$label,"selected":true}]}}}"""
                val view = AccountPoolProjection().parse(payload).getValue("head")
                assertTrue(view.selectionUnknown)
                assertEquals(listOf("primary"), view.accounts.map { it.label })
                assertUnknown(view)
            }
        }
    }

    @Test
    fun `direct rejected or unresolved selected labels never borrow primary windows`() {
        for (label in listOf("ops@example.com", "", "${0x1b.toChar()}[31mprivate", "missing")) {
            assertUnknown(HeadAccountPoolView(label, listOf(primary), null))
        }
    }

    @Test
    fun `direct dropped selected account never falls back to the valid primary`() {
        val rejected = primary.copy(label = "ops@example.com", primary = false, selected = true)
        assertUnknown(HeadAccountPoolView(null, listOf(primary, rejected), null))
    }

    @Test
    fun `absent or null selection keeps head-wide primary fallback despite a rejected nonselected account`() {
        for (selection in listOf("", "\"selected_label\":null,")) {
            val payload = """{"head":{"account_pool":{$selection
                "accounts":[$primaryJson,{"label":"ops@example.com","selected":false}]}}}"""
            val view = AccountPoolProjection().parse(payload).getValue("head")
            assertFalse(view.selectionUnknown)
            assertPrimary(view)
        }
        val rejected = primary.copy(label = "ops@example.com", primary = false)
        assertPrimary(HeadAccountPoolView(null, listOf(primary, rejected), null))
        assertEquals("on no account (0 of 0 open)", text.summary(HeadAccountPoolView(null, emptyList(), null)))
    }

    @Test
    fun `valid selection and unknown-selection metadata survive repeated renderer validation`() {
        val view = HeadAccountPoolView("primary", listOf(primary), null)
        assertPrimary(view)
        assertUnknown(view.copy(selectionUnknown = true))
    }

    private fun assertUnknown(view: HeadAccountPoolView) {
        val expected = "selection unknown (1 of 1 open)"
        assertEquals(expected, text.summary(view))
        assertEquals(expected, text.check("head", view).detail)
    }

    private fun assertPrimary(view: HeadAccountPoolView) {
        val expected = "on primary (1 of 1 open) · 5h 12% · 7d 34%"
        assertEquals(expected, text.summary(view))
        assertEquals(expected, text.check("head", view).detail)
    }
}

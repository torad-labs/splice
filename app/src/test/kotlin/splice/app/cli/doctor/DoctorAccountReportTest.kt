// NEW: account auth holds survive the doctor allowlist without exposing provider identity or free text.
package splice.app.cli.doctor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.app.cli.status.AccountPoolProjection
import splice.core.config.StatePaths
import splice.topology.TopologyLoader
import java.nio.file.Path

class DoctorAccountReportTest {
    @TempDir
    lateinit var tmp: Path

    private val topology = TopologyLoader.parse(
        """
        [daemon]
        control_port = 3999
        [providers.codex]
        dialect = "openai-responses"
        base_url = "https://chatgpt.example.invalid/backend-api/codex"
        auth = { kind = "chatgpt-oauth" }
        [[providers.codex.models]]
        id = "gpt-6-astra"
        context_window = 272000
        [heads.codex]
        provider = "codex"
        port = 3998
        discovery_prefix = "claudex--"
        pinned_model = "gpt-6-astra"
        """.trimIndent(),
    )

    @Test
    fun `the accounts block carries labels, flags, windows and the switch, keyed by head`() {
        val view = AccountPoolProjection().parse(
            """{"codex":{"account_pool":{"selected_label":"work","accounts":[""" +
                """{"label":"primary","primary":true,"selected":false,"available":false,"plan":"plus",""" +
                """"auth_excluded_until_epoch_millis":1700000300000,""" +
                """"auth_exclusion_reason":"terminal_401",""" +
                """"five_hour_used_percent":100.0,"seven_day_used_percent":61.5,""" +
                """"auth":{"account_id":"acct-SECRET","email":"ops@example.com"}},""" +
                """{"label":"work","primary":false,"selected":true,"available":true}],""" +
                """"last_switch":{"from":"primary","to":"work","reason":"7d window exhausted",""" +
                """"at_epoch_millis":7}}}}""",
        )
        val text = report(DoctorRun(topology, emptyList(), view)).toString()
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("example.com"))
        val accounts = Json.parseToJsonElement(text).jsonObject.getValue("accounts").jsonObject
        val codex = accounts.getValue("codex").jsonObject
        assertEquals("work", codex.getValue("selected").jsonPrimitive.content)
        val reportedAccounts = codex.getValue("accounts").jsonArray.map { it.jsonObject }
        assertEquals(listOf("primary", "work"), reportedAccounts.map { it.getValue("label").jsonPrimitive.content })
        assertEquals(
            "1700000300000",
            reportedAccounts.first().getValue("auth_excluded_until_epoch_millis").jsonPrimitive.content,
        )
        assertEquals(
            "terminal_401",
            reportedAccounts.first().getValue("auth_exclusion_reason").jsonPrimitive.content,
        )
        val switch = codex.getValue("last_switch").jsonObject
        assertEquals("7d window exhausted", switch.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `doctor redacts every auth exclusion reason string`() {
        val account = HeadAccountView(
            label = "primary",
            primary = true,
            selected = true,
            available = false,
            plan = null,
            fiveHourUsedPercent = null,
            fiveHourResetEpochSeconds = null,
            sevenDayUsedPercent = null,
            sevenDayResetEpochSeconds = null,
            authExcludedUntilEpochMillis = 1_700_000_300_000L,
            authExclusionReason = "terminal 401 for ops@example.com",
        )
        val run = DoctorRun(
            topology,
            emptyList(),
            mapOf("codex" to HeadAccountPoolView("primary", listOf(account), null)),
        )

        val text = report(run).toString()

        assertFalse(text.contains("ops@example.com"), text)
        assertTrue(text.contains("auth_exclusion_reason"), text)
    }

    private fun report(run: DoctorRun): JsonObject {
        val env = mapOf(
            "CLAUDEX_STATE_DIR" to tmp.resolve("state").toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
        )
        return DoctorReport(
            envReader = { env[it] },
            claudeVersion = { "2.1.257 (Claude Code) for ops@example.com" },
            home = tmp,
            statePaths = StatePaths(envReader = { env[it] }),
        ).build(run, withLogs = false)
    }
}

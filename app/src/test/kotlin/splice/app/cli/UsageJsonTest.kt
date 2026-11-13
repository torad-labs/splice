package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput

/** What `splice usage --json` prints for a daemon's accounts reply. */
class UsageJsonTest {
    private val usage = UsageJson(TerminalOutput {}, TerminalOutput {})

    private val pooled = """{"label":"account-1","display_name":"Work","kind":"client","plan":"max",""" +
        """"email":"someone@example.test","heads":["claude-splice"],""" +
        """"five_hour_used_percent":41.5,"five_hour_reset_epoch_seconds":1794549600,"five_hour_current":true,""" +
        """"seven_day_used_percent":63,"seven_day_reset_epoch_seconds":1794981600,"seven_day_current":true,""" +
        """"seven_day_models":[{"model":"fable","used_percent":82,"resets_at":1794981600}],""" +
        """"models_held":[{"model":"fable","until_epoch_seconds":1794981600,"claim":"limit"}],""" +
        """"observed_at_epoch_seconds":1794540000}"""

    private val single = """{"label":null,"display_name":"ChatGPT","kind":"chatgpt-oauth","plan":"pro",""" +
        """"heads":["claudex"],"five_hour_used_percent":null,"five_hour_current":false,""" +
        """"seven_day_used_percent":12,"seven_day_reset_epoch_seconds":1794900000,"seven_day_current":false,""" +
        """"observed_at_epoch_seconds":1794540100}"""

    private val reply = """{"accounts":[$single,$pooled],""" +
        """"head_pools":{"claude-splice":{"account_pool":{"next_target_label":"account-1"}}}}"""

    @Test
    fun `each command lists its logins with their plan windows, in the order the commands are given`() {
        val commands = mapOf("claude-splice" to "claude", "claudex" to "claudex", "idle" to "idle")

        val document = usage.document(reply, commands)

        val work = """{"label":"account-1","name":"Work","kind":"client","plan":"max",""" +
            """"five_hour":{"used_percent":41.5,"resets_at_epoch_seconds":1794549600,"current":true},""" +
            """"seven_day":{"used_percent":63,"resets_at_epoch_seconds":1794981600,"current":true},""" +
            """"models":[{"model":"fable","used_percent":82,"resets_at_epoch_seconds":1794981600}],""" +
            """"held":[{"model":"fable","until_epoch_seconds":1794981600}],""" +
            """"observed_at_epoch_seconds":1794540000}"""
        val chatgpt = """{"label":null,"name":"ChatGPT","kind":"chatgpt-oauth","plan":"pro","five_hour":null,""" +
            """"seven_day":{"used_percent":12,"resets_at_epoch_seconds":1794900000,"current":false},""" +
            """"models":[],"held":[],"observed_at_epoch_seconds":1794540100}"""
        assertEquals(
            """{"version":1,"commands":[""" +
                """{"head":"claude-splice","command":"claude","next":"account-1","accounts":[$work]},""" +
                """{"head":"claudex","command":"claudex","next":null,"accounts":[$chatgpt]}]}""",
            document.toString(),
        )
    }

    @Test
    fun `a reply that is not the accounts reply gives no document`() {
        assertNull(usage.document("""{"error":"unauthorized"}""", emptyMap()))
        assertNull(usage.document("not json", emptyMap()))
    }
}

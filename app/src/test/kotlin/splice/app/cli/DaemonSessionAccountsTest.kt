package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.sessions.list.SessionAccountKeys
import splice.sessions.list.SessionAccountLine

/** The CLI joins the daemon's session accounts by head AND session id (review of adf35c39e, finding 2). */
class DaemonSessionAccountsTest {
    private val accounts = DaemonSessionAccounts()

    private fun row(head: String, availability: String, account: String?, pin: String?): String {
        val fields = listOfNotNull(
            """"session_id":"s-1"""",
            """"head":"$head"""",
            """"availability":"$availability"""",
            account?.let { """"account":"$it"""" },
            pin?.let { """"account_pin":"$it"""" },
        )
        return fields.joinToString(",", "{", "}")
    }

    @Test
    fun `one session id on two heads keeps each head its own account and pin`() {
        val claudex = row("claudex", "live", "primary", "work")
        val grok = row("grok", "live", "team", null)
        val body = """{"sessions":[$claudex,$grok]}"""

        val read = accounts.lines(body)

        assertEquals(SessionAccountLine("primary", "work"), read[SessionAccountKeys.of("claudex", "s-1")])
        assertEquals(SessionAccountLine("team", null), read[SessionAccountKeys.of("grok", "s-1")])
    }

    @Test
    fun `a gone registration never overwrites a live one whichever comes last`() {
        val live = row("claudex", "live", "primary", "work")
        val gone = row("claudex", "gone", null, null)

        for (order in listOf("$live,$gone", "$gone,$live")) {
            val read = accounts.lines("""{"sessions":[$order]}""")

            assertEquals(SessionAccountLine("primary", "work"), read[SessionAccountKeys.of("claudex", "s-1")], order)
        }
    }
}

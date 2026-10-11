// Review finding 6: signing the primary account in again under its own label works, and the label stays refused for a
// new account. Server half of the console's "Sign in again" on a pooled first account.
package splice.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import java.nio.file.Files
import java.nio.file.Path

class PrimaryLabelLoginTest {

    @TempDir
    lateinit var dir: Path

    private val loginIo = LoginIo(TerminalOutput {})

    @Test
    fun `signing the primary account in again under its own label rewrites the primary`() {
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, """{"tokens":{"access_token":"revoked-primary"}}""")
        val account = OAuthAccountFiles().loginAccount(AuthKind.ChatgptOAuth, primary, "primary")
        val replacement = """{"tokens":{"access_token":"replacement-primary"}}"""

        assertTrue(loginIo.persistIfSignedIn(primary, replacement, account))

        assertTrue(account.primary)
        assertEquals(replacement, Files.readString(primary))
        assertFalse(Files.exists(dir.resolve("chatgpt-oauth")), "no labeled pool entry is created for the primary")
    }

    @Test
    fun `the primary label is still refused for a new account when there is no primary yet`() {
        val failure = assertThrows(OAuthAccountRefused::class.java) {
            OAuthAccountFiles().loginAccount(AuthKind.ChatgptOAuth, dir.resolve("codex.json"), "primary")
        }

        assertTrue(failure.reason.contains("reserved label"), failure.reason)
    }
}

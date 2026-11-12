// + Add account on a ChatGPT command, when the sign-in comes back as the account the command's first login already
// holds (Marcos, Nov 11, 2026: "I had issues before like this adding new accounts"). The sign-in page signs in
// whichever account its browser holds, so that is what an add most often comes back as. It filed a second login for
// that one account, under a label of its own, and the page drew a second card for it.
package splice.oauth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.oauth.codex.LoginCodex
import java.nio.file.Files
import java.nio.file.Path

class AccountLoginTwinTest {

    @TempDir
    lateinit var dir: Path

    private val loginIo = LoginIo(TerminalOutput {})

    @Test
    fun `an automatic codex login that comes back as the primary's account adds nothing and says so`() {
        val primary = dir.resolve("codex.json")
        // three dot-joined parts, as a ChatGPT access token has, with empty claims; joined here, so the fixture holds
        // no literal a secret scanner reads as a key
        val token = listOf("header", "e30", "signature").joinToString(".")
        val authJson = """{"tokens":{"access_token":"$token","account_id":"private-account-id"}}"""
        Files.writeString(primary, authJson)
        val account = requireNotNull(LoginCodex().spec("codex", primary, "auto").account)

        val filed = runBlocking { loginIo.persistIfSignedIn(primary, authJson, account) }

        assertFalse(filed)
        assertEquals(OAuthLoginRefusal.ALREADY_THERE, account.refusalKind())
        assertTrue(account.refusal().orEmpty().contains("already this command's first login"))
        assertFalse(account.refusal().orEmpty().contains("private-account-id"))
        assertFalse(Files.exists(OAuthAccountFiles().poolDir(AuthKind.ChatgptOAuth, primary)))
        assertEquals(authJson, Files.readString(primary))
    }
}

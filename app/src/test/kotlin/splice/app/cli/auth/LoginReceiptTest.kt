// Split from AccountLoginTest when the account files moved to integrations/oauth (LAYOUT-01): what the
// CLI's sign-in receipt says. The label a login records is oauth's to prove; that the receipt names
// the RECORDED label, never the requested `auto`, is this verb's.
package splice.app.cli.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.login.LoginOutcomeFile
import splice.core.config.StatePaths
import splice.oauth.SignInPersistence
import splice.oauth.codex.LoginCodex
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class LoginReceiptTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the labeled login receipt says saved-for-restart, never using`() {
        val labeled = CliSignIn().outcomeText("claudex", ok = true, label = "work")
        assertTrue(labeled.contains("signed in as 'work'") && labeled.contains("splice restart"), labeled)
        assertFalse(labeled.contains("using"), "the labeled account is not what this session uses: $labeled")
        assertTrue(CliSignIn().outcomeText("claudex", ok = true, label = null).contains("using the new credentials"))
        assertTrue(CliSignIn().outcomeText("claudex", ok = false, label = "work").contains("claudex login"))
    }

    // Which label a login records (the token-shaped one, collision suffix and all) is oauth's
    // AccountLoginTest; this arm holds the receipt to whatever the account recorded.
    @Test
    fun `codex auto receipt names the label the login recorded, never auto`() {
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{}")
        val account = requireNotNull(LoginCodex().spec("codex", primary, "auto").account)
        val claims = """
            {"https://api.openai.com/auth":{
              "chatgpt_account_id":"private-account-id","chatgpt_plan_type":"Plus"
            }}
        """.trimIndent()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        val token = "header.$payload.signature"
        val authJson = """{"tokens":{"access_token":"$token","account_id":"private-account-id"}}"""
        assertTrue(SignInPersistence().persist(primary, authJson, account))
        val persisted = requireNotNull(account.persistedLabel())
        assertNotEquals("auto", persisted)

        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", dir.toString())
        try {
            CliSignIn().writeLoginOutcome("codex", ok = true, account = account)
            val receipt = requireNotNull(LoginOutcomeFile.consume(StatePaths().stateDir, "codex"))
            assertTrue(receipt.contains("signed in as '$persisted'"), receipt)
            assertFalse(receipt.contains("'auto'"), receipt)
        } finally {
            System.setProperty("user.home", savedHome)
        }
    }
}

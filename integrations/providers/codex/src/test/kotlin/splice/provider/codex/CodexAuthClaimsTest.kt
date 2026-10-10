// NEW: Oct 10, 2026 — two ChatGPT accounts on one command are told apart on Accounts by the sign-in's own claims: the
// description names the user id and email the id_token carries, and never a token.
package splice.provider.codex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.RefreshAttempt
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.writeText

class CodexAuthClaimsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = scope.cancel()

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("""{"alg":"none"}""".toByteArray())}." +
            "${enc.encodeToString(payloadJson.toByteArray())}.sig"
    }

    private fun provider(tmp: Path, json: String): CodexAuthProvider {
        val authPath = tmp.resolve(".codex/auth.json")
        Files.createDirectories(authPath.parent)
        authPath.writeText(json)
        return CodexAuthProvider(
            authPath = authPath,
            authCacheMs = 60_000,
            clock = { 1L },
            nowIso = { "2026-10-10T00:00:00Z" },
            refreshCall = { RefreshAttempt.Denied("test-denied") },
            prefetchScope = scope,
        )
    }

    @Test
    fun `describe names the account the sign-in proves, by its user id and email`(@TempDir tmp: Path) = runTest {
        val id = jwt("""{"sub":"user-abc","email":"work@example.com"}""")
        val d = provider(tmp, """{"tokens":{"access_token":"secret","id_token":"$id"}}""").describe()
        assertEquals("user-abc", d.fields["account_uuid"])
        assertEquals("work@example.com", d.fields["account_email"])
        assertFalse(d.fields.values.any { it.contains("secret") || it == id })
    }

    @Test
    fun `a credential with no id token names no account`(@TempDir tmp: Path) = runTest {
        val d = provider(tmp, """{"tokens":{"access_token":"secret"}}""").describe()
        assertNull(d.fields["account_uuid"])
        assertNull(d.fields["account_email"])
    }
}

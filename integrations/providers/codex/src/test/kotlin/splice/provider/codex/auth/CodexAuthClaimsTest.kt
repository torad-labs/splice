// NEW: Oct 10, 2026 — two ChatGPT accounts on one command are told apart on Accounts. Auth description carries the
// proved email for default naming, while user id, account id and token material remain masked.
package splice.provider.codex.auth

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
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
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
    fun `describe carries the proved email for naming without exposing user id or token`(
        @TempDir tmp: Path,
    ) = runTest {
        val id = jwt("""{"sub":"user-abc","email":"work@example.com"}""")
        val json = """{"tokens":{"access_token":"secret","account_id":"acct-1","id_token":"$id"}}"""
        val d = provider(tmp, json).describe()
        assertEquals(AccountRef.of("acct-1"), d.fields[ACCOUNT_REF_FIELD])
        assertEquals("work@example.com", d.fields["email"])
        assertFalse(d.fields.values.any { "user-abc" in it || "acct-1" in it }, "raw user and account IDs stay masked")
        assertFalse(d.fields.values.any { it.contains("secret") || it == id })
    }

    @Test
    fun `a credential with no account id names no account`(@TempDir tmp: Path) = runTest {
        val d = provider(tmp, """{"tokens":{"access_token":"secret"}}""").describe()
        assertNull(d.fields[ACCOUNT_REF_FIELD])
    }
}

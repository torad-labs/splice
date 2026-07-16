// NEW: CodexProvider.extraHeaders is the SOLE controller of ChatGPT-Account-ID (UpstreamClient no
// longer adds it in applyAuth — that made account_id_header=false a no-op). This pins the gate:
// flag=true + a Bearer with an account id => header present; flag=false => absent, even with an
// account id available.
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.provider.codex.CodexProvider
import kotlin.time.Duration.Companion.seconds

class CodexProviderTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials() = Credentials.Bearer("tok", accountId = "acct-123")
        override suspend fun refresh() = credentials()
        override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
    }

    private fun provider(accountIdHeader: Boolean) = CodexProvider(
        key = "codex",
        label = "claudex",
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272000L)),
            defaultContextWindow = 272000L,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = fakeAuth,
        baseUrl = "https://x",
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        showReasoning = "text",
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
        accountIdHeader = accountIdHeader,
    )

    @Test
    fun `account id header present when the flag is on`() = runBlocking {
        val creds = Credentials.Bearer("tok", accountId = "acct-123")
        val headers = provider(accountIdHeader = true).extraHeaders(creds)
        assertEquals("acct-123", headers["ChatGPT-Account-ID"])
        assertEquals("text/event-stream", headers["Accept"])
    }

    @Test
    fun `account id header absent when the flag is off - even with an account id available`() = runBlocking {
        val creds = Credentials.Bearer("tok", accountId = "acct-123")
        val headers = provider(accountIdHeader = false).extraHeaders(creds)
        assertFalse(headers.containsKey("ChatGPT-Account-ID")) // the flag actually suppresses it
        assertTrue(headers.containsKey("Accept"))
    }
}

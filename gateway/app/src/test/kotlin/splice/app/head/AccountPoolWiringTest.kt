package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.dialect.chat.ChatQuirks
import splice.gateway.usage.QuotaTracker
import splice.provider.openai.OpenAiChatProvider
import splice.spi.AccountPool
import splice.spi.PoolAccount
import splice.spi.ProviderTuning
import splice.spi.Selection
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class AccountPoolWiringTest {
    @Test
    fun `each head gets isolated pool state for the same accounts and session`(@TempDir tmp: Path) {
        val wired = wired(tmp)
        val firstTrackers = trackers(tmp.resolve("first"), primaryUsed = 100.0)
        val secondTrackers = trackers(tmp.resolve("second"), primaryUsed = 10.0)
        val pools = HeadAccountPools()

        val first = requireNotNull(pools.build(wired, firstTrackers))
        val second = requireNotNull(pools.build(wired, secondTrackers))

        assertNotSame(first, second)
        assertEquals("backup", chosen(first).label)
        assertEquals("primary", chosen(second).label)
        assertEquals("backup", first.view(SESSION).selectedLabel)
        assertEquals("primary", second.view(SESSION).selectedLabel)
    }

    @Test
    fun `a missing primary is wired but never selected ahead of a readable backup`(@TempDir tmp: Path) {
        val wired = wired(tmp, primaryPresent = false)
        val pools = HeadAccountPools()
        val pool = requireNotNull(pools.build(wired, trackers(tmp, primaryUsed = 0.0)))

        assertEquals("backup", chosen(pool).label)
        val primary = pool.view(SESSION).accounts.single { it.label == "primary" }
        assertEquals(false, primary.available)
        assertEquals(false, primary.credentialPresent)
        val projected = requireNotNull(pools.source(pool)).view(SESSION)
        assertEquals(false, projected.accounts.single { it.primary }.credentialPresent)
        assertEquals(true, projected.accounts.single { !it.primary }.credentialPresent)
    }

    private fun trackers(dir: Path, primaryUsed: Double): Map<String, QuotaTracker> {
        val primary = QuotaTracker(dir.resolve("primary.json"))
        val backup = QuotaTracker(dir.resolve("backup.json"))
        primary.record(quota(primaryUsed))
        backup.record(quota(20.0))
        return mapOf("primary" to primary, "backup" to backup)
    }

    private fun wired(tmp: Path, primaryPresent: Boolean = true): Wired {
        val primary = TestAuth("primary-token")
        val backup = TestAuth("backup-token")
        val defaultAuth = if (primaryPresent) primary else backup
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-test--",
            models = listOf(ModelEntry("model", "Model", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        )
        val provider = OpenAiChatProvider(
            ProviderTuning(
                key = "head",
                label = "Head",
                catalog = catalog,
                pinnedModel = "model",
                auth = defaultAuth,
                baseUrl = "https://example.invalid",
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            ChatQuirks("test"),
            ReasoningDisplay.TEXT,
        )
        return Wired(
            provider,
            defaultAuth,
            listOf(
                WiredAccount(
                    "primary",
                    true,
                    primary,
                    tmp.resolve("primary-quota.json"),
                    credentialPresent = primaryPresent,
                ),
                WiredAccount("backup", false, backup, tmp.resolve("backup-quota.json")),
            ),
        )
    }

    private fun chosen(pool: AccountPool): PoolAccount = (pool.select(SESSION) as Selection.Chosen).account.account

    private fun quota(used: Double): QuotaSnapshot = QuotaSnapshot(
        fiveHour = QuotaWindow(used, System.currentTimeMillis() / 1_000L + 3_600L, 18_000L),
        sevenDay = QuotaWindow(used, System.currentTimeMillis() / 1_000L + 86_400L, 604_800L),
    )
}

private class TestAuth(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

private const val SESSION = "same-session"

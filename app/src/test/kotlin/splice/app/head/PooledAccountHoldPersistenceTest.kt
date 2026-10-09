package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.chat.ChatQuirks
import splice.head.usage.QuotaTracker
import splice.provider.openai.OpenAiChatProvider
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val SIX_DAYS_MS = 6L * 24 * 3_600 * 1_000

/** V4-412: a pooled head's accounts each keep their own provider hold, named by head and label, so a
 *  restart that rebuilds the pool still reads out of quota once every account is refusing. */
class PooledAccountHoldPersistenceTest {
    private val quiet = LogSink { }

    private fun wired(tmp: Path): Wired {
        val primary = PooledTestAuth()
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
                auth = primary,
                baseUrl = "https://example.invalid",
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            ChatQuirks("test"),
            ReasoningDisplay.TEXT,
        )
        return Wired(
            provider,
            primary,
            listOf(
                WiredAccount("primary", true, primary, tmp.resolve("primary-quota.json")),
                WiredAccount("backup", false, PooledTestAuth(), tmp.resolve("backup-quota.json")),
            ),
        )
    }

    private fun trackers(tmp: Path) = mapOf(
        "primary" to QuotaTracker(tmp.resolve("primary.json")),
        "backup" to QuotaTracker(tmp.resolve("backup.json")),
    )

    private fun chosen(pool: AccountPool): PoolAccount = (pool.select("s") as Selection.Chosen).account.account

    @Test
    fun `every account refusing until a known instant still reads out of quota after the pool is rebuilt`(
        @TempDir tmp: Path,
    ) {
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        val wired = wired(tmp)
        val files = ProviderHoldFiles(state, quiet)
        val pools = HeadAccountPools()
        val pool = requireNotNull(pools.build(wired, trackers(tmp), files.forAccounts("head", wired)))
        listOf("primary", "backup").forEach { label ->
            pool.pin(label)
            chosen(pool).cooldown.markUnavailable(SIX_DAYS_MS)
        }
        listOf("primary", "backup").forEach {
            assertTrue(Files.exists(state.stateDir.resolve("head-$it-provider-hold.json")), it)
        }

        val rebuilt = requireNotNull(pools.build(wired, trackers(tmp), files.forAccounts("head", wired)))
        val unstored = requireNotNull(pools.build(wired, trackers(tmp)))

        val remaining = rebuilt.providerResetForMs
        assertTrue(remaining in SIX_DAYS_MS - 60_000..SIX_DAYS_MS, "$remaining")
        assertEquals(0L, unstored.providerResetForMs, "no store, no memory")
    }
}

private class PooledTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

package splice.app.head

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.order.AccountOrderStore
import splice.accounts.order.HeadAccountOrderSource
import splice.accounts.pool.AccountPoolJson
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.dialect.chat.ChatQuirks
import splice.head.usage.QuotaTracker
import splice.provider.openai.OpenAiChatProvider
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.ProviderHold
import splice.upstream.retry.RateLimitCooldown
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

    @Test
    fun `the auth projection carries a provider-held horizon but never a usage-only full window`() {
        for (providerHeld in listOf(false, true)) {
            val account = PoolAccount(
                label = "primary",
                primary = true,
                auth = TestAuth("test"),
                quota = object : AccountQuotaSource {
                    override val held: Boolean = providerHeld
                    override fun snapshot(): QuotaSnapshot = QuotaSnapshot(
                        fiveHour = QuotaWindow(10.0, 8200L, 18000L),
                        sevenDay = QuotaWindow(100.0, 433000L, 604800L),
                    )
                },
                cooldown = RateLimitCooldown(ElapsedClock { 0 }),
            )
            val pool = AccountPool(listOf(account), WallClock { 1000000L })
            val view = requireNotNull(HeadAccountPools().source(pool)).view(null)
            val json = buildJsonObject { AccountPoolJson().write(this, view) }
            val projected = json["account_pool"]?.jsonObject?.get("accounts")?.jsonArray?.single()?.jsonObject
            assertEquals(
                "433000".takeIf { providerHeld },
                projected?.let { JsonScalars.str(it, "blocked_until_epoch_seconds") },
            )
        }
    }

    @Test
    fun `persisted order reaches the same selector now and after head reconstruction`(@TempDir tmp: Path) {
        val wired = wired(tmp)
        val pools = HeadAccountPools()
        val store = AccountOrderStore(tmp.resolve("order.json"))
        val first = requireNotNull(pools.build(wired, trackers(tmp.resolve("first"), primaryUsed = 0.0)))
        val source = pools.source(first, "one", store) as HeadAccountOrderSource
        assertTrue(source.setOrder(listOf("backup", "primary")))
        assertEquals("backup", chosen(first).label)
        val second = requireNotNull(pools.build(wired, trackers(tmp.resolve("second"), primaryUsed = 0.0)))
        val restoredStore = AccountOrderStore(tmp.resolve("order.json"))
        val restored = pools.source(second, "one", restoredStore) as HeadAccountOrderSource
        assertEquals(listOf("backup", "primary"), restored.order())
        assertEquals("backup", chosen(second).label)
        val other = requireNotNull(pools.build(wired, trackers(tmp.resolve("other"), primaryUsed = 0.0)))
        pools.source(other, "two", store)
        assertEquals("primary", chosen(other).label)
    }

    @Test
    fun `a restart keeps the operator's order and the login the provider holds`(@TempDir tmp: Path) {
        val two = wired(tmp)
        val third = WiredAccount("third", false, TestAuth("third-token"), tmp.resolve("third-quota.json"))
        val wired = two.copy(accounts = two.accounts + third)
        val pools = HeadAccountPools()
        val holdFile = { label: String -> tmp.resolve("head-$label-provider-hold.json") }
        val holds = { wired.accounts.associate { it.label to FileProviderHoldStore(holdFile(it.label)) {} } }
        val trackers = { dir: String ->
            trackers(tmp.resolve(dir), primaryUsed = 0.0) + ("third" to thirdTracker(tmp.resolve(dir)))
        }
        val first = requireNotNull(pools.build(wired, trackers("first"), holds()))
        val orders = { AccountOrderStore(tmp.resolve("order.json")) }
        val source = pools.source(first, "head", orders()) as HeadAccountOrderSource
        assertTrue(source.setOrder(listOf("third", "primary", "backup")))
        assertEquals("third", chosen(first).label)
        // What the first daemon wrote when the provider named third's 5-hour window spent.
        val reset = System.currentTimeMillis() / 1_000L + 7_200L
        FileProviderHoldStore(holdFile("third")) {}.save(ProviderHold(null, PlanLimit("five_hour", reset)))

        val second = requireNotNull(pools.build(wired, trackers("second"), holds()))
        val restored = pools.source(second, "head", orders()) as HeadAccountOrderSource

        assertEquals(listOf("third", "primary", "backup"), restored.order())
        assertEquals("primary", chosen(second).label, "third is held, so the next login in the order serves")
        assertEquals(false, second.view(SESSION).accounts.single { it.label == "third" }.available)
    }

    private fun thirdTracker(dir: Path): QuotaTracker =
        QuotaTracker(dir.resolve("third.json")).also { it.record(quota(20.0)) }

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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.spi.AccountCredentialIdentitySource
import splice.spi.AccountCredentialIdentitySource.CredentialPresence
import splice.spi.AccountPool
import splice.spi.AccountQuotaSource
import splice.spi.AccountSelection
import splice.spi.AccountView
import splice.spi.PoolAccount
import splice.spi.RateLimitCooldown
import splice.spi.RateLimitTurn
import splice.spi.RetryDecision
import splice.spi.RetryNotice
import splice.spi.Selection
import java.util.concurrent.atomic.AtomicReference

class AccountPoolTest {
    /** [AccountPool.select] returns a sealed [Selection]; the chosen-case tests read the account. */
    private fun AccountPool.chosen(sessionId: String?): AccountSelection =
        when (val selection = select(sessionId)) {
            is Selection.Chosen -> selection.account
            is Selection.Exhausted -> throw AssertionError("expected a chosen account, got exhausted")
        }

    /** The exhaustion refusal is a value now, not a thrown exception. */
    private fun AccountPool.exhausted(sessionId: String?): Selection.Exhausted =
        when (val selection = select(sessionId)) {
            is Selection.Exhausted -> selection
            is Selection.Chosen -> throw AssertionError("expected exhaustion, got a chosen account")
        }

    @Test
    fun `quota exhaustion switches only the affected session to the lowest weekly account`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val busier = fixture.account("plus-b", weekly = 40.0)
        val quieter = fixture.account("plus-a", weekly = 10.0)
        val pool = fixture.pool(primary, busier, quieter)

        assertSame(primary, pool.chosen("session-a").account)
        assertSame(primary, pool.chosen("session-b").account)
        fixture.setQuota(primary, fixture.quota(five = 100.0, reset = 2_000L))

        val switched = pool.chosen("session-a")
        assertSame(quieter, switched.account)
        assertEquals("5-hour quota exhausted", switched.switch?.reason)
        assertTrue(switched.cacheCold)
        assertEquals("primary", pool.view("session-b").selectedLabel)
    }

    @Test
    fun `an in-flight selection stays fixed and the next turn observes a long rate limit`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        val inFlight = pool.chosen("session")
        primary.cooldown.markUnavailable(60_000L)
        val next = pool.chosen("session")

        assertSame(primary, inFlight.account)
        assertSame(backup, next.account)
        assertEquals("rate limit exceeds turn wait budget", next.switch?.reason)
    }

    @Test
    fun `an armed fail-fast horizon alone does not switch accounts`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        assertSame(primary, pool.chosen("session").account)

        primary.cooldown.arm(30_000L)
        val next = pool.chosen("session")

        assertSame(primary, next.account)
        assertEquals(null, next.switch)
        assertTrue(pool.view("session").accounts.single { it.label == "primary" }.available)
    }

    @Test
    fun `a short 429 backs off and still keeps the next selection warm`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary, fixture.account("plus-a"))
        assertSame(primary, pool.chosen("session").account)

        val plan = primary.cooldown.rateLimitedPlan(
            pushbackMs = 1_000L,
            turn = RateLimitTurn(primary.cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )
        val next = pool.chosen("session")

        // V4-48: a short pushback now takes the BACKOFF branch — it is waited out, not surrendered —
        // and a waited-out 429 arms NOTHING, so no follower is failed fast for that interval.
        assertEquals(RetryDecision.BACKOFF, plan.decision)
        assertEquals(0L, primary.cooldown.unavailableForMs())
        assertEquals(0L, primary.cooldown.remainingMs(), "the wait path leaves the head unarmed")
        assertSame(primary, next.account)
        assertFalse(next.cacheCold)
        assertEquals(null, next.switch)
        assertEquals(null, pool.view("session").lastSwitch)
    }

    @Test
    fun `a hostile reset horizon releases the primary within the recovery ceiling`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        primary.cooldown.markUnavailable(Long.MAX_VALUE)

        assertSame(backup, pool.chosen("session").account)
        val blocked = fixture.pool(primary).exhausted("blocked")
        assertEquals(605_800L, blocked.earliestResetEpochSeconds)
        assertTrue(blocked.message.contains("1970-01-08T00:16:40Z"))
        fixture.advanceElapsed(120_000L)
        assertSame(primary, pool.chosen("session").account)
    }

    @Test
    fun `cooldown reset seconds fit even at the wall clock extremes`() {
        for (at in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val fixture = Fixture()
            fixture.now.set(at)
            val primary = fixture.account("primary", primary = true)
            primary.cooldown.markUnavailable(Long.MAX_VALUE)

            val failure = fixture.pool(primary).exhausted("session")

            assertEquals(at / 1_000L + 604_800L, failure.earliestResetEpochSeconds)
        }
    }

    @Test
    fun `all exhausted reports the provider reset beyond the bounded re-probe horizon`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary)
        primary.cooldown.markUnavailable(86_400_000L)

        val failure = pool.exhausted("session")

        assertEquals(87_400L, failure.earliestResetEpochSeconds)
        assertEquals(120_000L, primary.cooldown.unavailableForMs())
    }

    @Test
    fun `a missing primary selects the readable labeled credential`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, credentialPresent = false)
        val backup = fixture.account("work")
        val pool = fixture.pool(primary, backup)

        val selected = pool.chosen("session")

        assertSame(backup, selected.account)
        assertEquals("primary", selected.switch?.from)
        assertEquals("account unavailable", selected.switch?.reason)
    }

    @Test
    fun `unsafe pool labels fail without echoing their bytes`() {
        val fixture = Fixture()

        val failure = assertThrows<IllegalArgumentException> {
            fixture.account("private@example.com", primary = true)
        }

        assertEquals("invalid OAuth account label", failure.message)
        assertFalse(failure.message.orEmpty().contains("private@example.com"))
    }

    @Test
    fun `sticky sessions return to primary at its first turn after reset`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0, reset = 2_000L)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        assertSame(backup, pool.chosen("session").account)
        fixture.now.set(2_000_000L)
        val returned = pool.chosen("session")

        assertSame(primary, returned.account)
        assertEquals("primary account reset", returned.switch?.reason)
        assertTrue(returned.cacheCold)
    }

    @Test
    fun `all exhausted reports the earliest account reset`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0, reset = 3_000L)
        val backup = fixture.account("plus-a", weekly = 100.0, reset = 2_000L)
        val pool = fixture.pool(primary, backup)

        val failure = pool.exhausted("session")

        assertEquals(2_000L, failure.earliestResetEpochSeconds)
        assertTrue(failure.message.contains("1970-01-01T00:33:20Z"))
        assertFalse(failure.message.contains("reset is 2000"))
    }

    @Test
    fun `a stale full quota without a reset does not disable an account forever`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0, reset = null)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        assertSame(primary, pool.chosen("session").account)
    }

    @Test
    fun `first turn starts cache cold when primary is already exhausted`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        val selected = pool.chosen("session")

        assertSame(backup, selected.account)
        assertEquals("primary", selected.switch?.from)
        assertEquals("plus-a", selected.switch?.to)
        assertTrue(selected.cacheCold)
    }

    @Test
    fun `null sessions re-evaluate without repeating a switch or exposing another choice`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0)
        val first = fixture.account("plus-a", weekly = 10.0)
        val second = fixture.account("plus-b", weekly = 20.0)
        val pool = fixture.pool(primary, first, second)

        val initial = pool.chosen(null)
        val repeated = pool.chosen(null)
        fixture.setQuota(first, fixture.quota(weekly = 30.0))
        fixture.setQuota(second, fixture.quota(weekly = 5.0))
        val moved = pool.chosen(null)

        assertSame(first, initial.account)
        assertTrue(initial.cacheCold)
        assertSame(first, repeated.account)
        assertFalse(repeated.cacheCold)
        assertSame(second, moved.account)
        assertTrue(moved.cacheCold)
        val view = pool.view(null)
        assertEquals(null, view.selectedLabel)
        assertTrue(view.accounts.none(AccountView::selected))
        assertEquals("plus-b", view.lastSwitch?.to)
    }

    @Test
    fun `safe view carries each provider quota window without identity fields`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        fixture.setQuota(
            primary,
            QuotaSnapshot(
                fiveHour = QuotaWindow(42.5, 1_234L, 18_000L),
                sevenDay = QuotaWindow(71.25, 5_678L, 604_800L),
                plan = "business",
            ),
        )
        val pool = fixture.pool(primary)
        pool.chosen("session")

        val view = pool.view("session").accounts.single()

        assertEquals("business", view.plan)
        assertEquals(42.5, view.fiveHourUsedPercent)
        assertEquals(1_234L, view.fiveHourResetEpochSeconds)
        assertEquals(71.25, view.sevenDayUsedPercent)
        assertEquals(5_678L, view.sevenDayResetEpochSeconds)
        assertFalse(view.toString().contains("private-account-id"))
    }

    @Test
    fun `concurrent selections preserve one sticky choice per session`() = runBlocking {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        val selections = List(100) {
            async(Dispatchers.Default) { pool.chosen("session").account }
        }.awaitAll()

        assertTrue(selections.all { it === backup })
        assertEquals("plus-a", pool.view("session").selectedLabel)
    }

    @Test
    fun `sticky session capacity evicts the least recently used without changing an issued turn`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true, five = 100.0)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        val issued = pool.chosen("session-1")
        pool.chosen("session-0")
        for (i in 2 until 4_096) pool.chosen("session-$i")
        assertFalse(pool.chosen("session-1").cacheCold)

        pool.chosen("overflow")

        assertEquals(null, pool.view("session-0").selectedLabel)
        assertEquals("plus-a", pool.view("session-1").selectedLabel)
        assertSame(backup, issued.account)
        assertTrue(pool.chosen("session-0").cacheCold, "an evicted session starts relative to primary again")
        assertEquals(null, pool.view("session-2").selectedLabel)
    }

    @Test
    fun `concurrent distinct sessions cannot grow stickiness past its capacity`() = runBlocking {
        val fixture = Fixture()
        val pool = fixture.pool(fixture.account("primary", primary = true))
        val ids = List(4_160) { "session-$it" }

        ids.map { id -> async(Dispatchers.Default) { pool.chosen(id) } }.awaitAll()

        assertEquals(4_096, ids.count { pool.view(it).selectedLabel != null })
        pool.reset()
        assertTrue(ids.all { pool.view(it).selectedLabel == null })
    }

    @Test
    fun `pool views are masked and reset clears runtime state`() = runBlocking {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.chosen("session")
        primary.cooldown.markUnavailable(60_000L)
        primary.cooldown.arm(60_000L)

        val view = pool.view("session")
        pool.reset()

        assertEquals(listOf("primary", "plus-a"), view.accounts.map { it.label })
        assertFalse(view.toString().contains("secret"))
        assertEquals(0L, primary.cooldown.unavailableForMs())
        assertEquals(0L, primary.cooldown.remainingMs())
        assertEquals(null, pool.view("session").selectedLabel)
        assertEquals("secret", (primary.auth.credentials() as Credentials.Bearer).token)
    }

    @Test
    fun `credential evidence is read off the sticky-session monitor`() {
        val now = AtomicReference(1_000_000L)
        val monitorHolder = AtomicReference<Any?>(null)
        var readUnderMonitor = false

        fun account(label: String, primary: Boolean): PoolAccount {
            val source = object : RefreshableAuthProvider, AccountCredentialIdentitySource {
                override suspend fun credentials(): Credentials = Credentials.Bearer("secret", "id")
                override suspend fun describe(): AuthDescription = AuthDescription(true, "test", emptyMap())
                override suspend fun refresh(): Credentials = credentials()
                override fun credentialIdentity(): CredentialFileIdentity? =
                    CredentialFileIdentity(1, 100L, "digest-$label")
                override fun credentialEvidence(): AccountCredentialIdentitySource.CredentialEvidence {
                    val monitor = monitorHolder.get()
                    if (monitor != null && Thread.holdsLock(monitor)) readUnderMonitor = true
                    return AccountCredentialIdentitySource.CredentialEvidence(
                        credentialIdentity(),
                        AccountCredentialIdentitySource.CredentialPresence.PRESENT,
                    )
                }
            }
            return PoolAccount(
                label = label,
                primary = primary,
                auth = source,
                quota = AccountQuotaSource { null },
                cooldown = RateLimitCooldown(ElapsedClock { 0L }),
            )
        }

        val pool = AccountPool(listOf(account("primary", true), account("plus-a", false)), WallClock(now::get))
        val sessions = AccountPool::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(pool)
        monitorHolder.set(sessions)

        pool.chosen("session")

        assertFalse(readUnderMonitor, "credential evidence must be read off the sticky-session monitor")
    }

    internal class Fixture {
        val now = AtomicReference(1_000_000L)
        private val quotas = mutableMapOf<PoolAccount, AtomicReference<QuotaSnapshot>>()
        private val identities = mutableMapOf<PoolAccount, AtomicReference<CredentialFileIdentity?>>()
        private val presences = mutableMapOf<PoolAccount, AtomicReference<CredentialPresence>>()
        private var elapsed = 0L
        private var revision = 1L

        fun pool(vararg accounts: PoolAccount): AccountPool = AccountPool(accounts.toList(), WallClock(now::get))

        fun account(
            label: String,
            primary: Boolean = false,
            five: Double = 0.0,
            weekly: Double = 0.0,
            reset: Long? = 2_000L,
            credentialPresent: Boolean = true,
            credentialIdentityKnown: Boolean = true,
        ): PoolAccount {
            val quota = AtomicReference(quota(five, weekly, reset))
            val rev = revision++
            val identity = AtomicReference(
                CredentialFileIdentity(rev, 100L, "digest-of-revision-$rev").takeIf {
                    credentialPresent && credentialIdentityKnown
                },
            )
            val presence = AtomicReference(
                when {
                    !credentialPresent -> CredentialPresence.MISSING
                    credentialIdentityKnown -> CredentialPresence.PRESENT
                    else -> CredentialPresence.UNKNOWN
                },
            )
            val auth = object : RefreshableAuthProvider, AccountCredentialIdentitySource {
                override suspend fun credentials(): Credentials = Credentials.Bearer("secret", "private-account-id")
                override suspend fun describe(): AuthDescription =
                    AuthDescription(true, "test", mapOf("token" to "***"))
                override suspend fun refresh(): Credentials = credentials()
                override fun credentialIdentity(): CredentialFileIdentity? = identity.get()
                override fun credentialPresence(): CredentialPresence = presence.get()
            }
            return PoolAccount(
                label = label,
                primary = primary,
                auth = auth,
                quota = AccountQuotaSource(quota::get),
                cooldown = RateLimitCooldown(ElapsedClock { elapsed }),
                credentialPresent = credentialPresent,
            ).also {
                quotas[it] = quota
                identities[it] = identity
                presences[it] = presence
            }
        }

        fun setQuota(account: PoolAccount, quota: QuotaSnapshot) {
            quotas.getValue(account).set(quota)
        }

        fun advanceElapsed(ms: Long) {
            elapsed += ms
        }

        fun advanceWall(ms: Long) {
            now.set(now.get() + ms)
        }

        fun rotateCredential(account: PoolAccount) {
            val rev = revision++
            identities.getValue(account).set(CredentialFileIdentity(rev, 100L, "digest-of-revision-$rev"))
            presences.getValue(account).set(CredentialPresence.PRESENT)
        }

        fun hideCredentialIdentity(account: PoolAccount): CredentialFileIdentity {
            presences.getValue(account).set(CredentialPresence.UNKNOWN)
            return checkNotNull(identities.getValue(account).getAndSet(null))
        }

        fun restoreCredentialIdentity(account: PoolAccount, identity: CredentialFileIdentity) {
            identities.getValue(account).set(identity)
            presences.getValue(account).set(CredentialPresence.PRESENT)
        }

        fun quota(five: Double = 0.0, weekly: Double = 0.0, reset: Long? = 2_000L): QuotaSnapshot = QuotaSnapshot(
            fiveHour = QuotaWindow(five, reset, 18_000L),
            sevenDay = QuotaWindow(weekly, reset, 604_800L),
        )
    }
}

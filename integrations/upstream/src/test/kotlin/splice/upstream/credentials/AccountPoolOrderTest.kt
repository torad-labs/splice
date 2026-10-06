// NEW: explicit account policy wins over weekly sorting and takes effect at the next turn boundary.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.retry.RateLimitCooldown
import java.util.concurrent.atomic.AtomicReference

class AccountPoolOrderTest {
    @Test
    fun `policy changes the next turn without losing the explicit pin override`() {
        val fixture = Fixture()
        val pool = fixture.pool
        fixture.quota("primary", sevenReset = 1_500L)
        assertEquals("primary", pool.nextTargetLabel())
        pool.select("session")
        pool.order = listOf("higher", "lower", "primary")
        assertEquals("higher", pool.nextTargetLabel("session"))
        val changed = (pool.select("session") as Selection.Chosen).account
        assertEquals("higher", changed.account.label)
        assertEquals("operator account order", changed.switch?.reason)
        assertEquals(listOf("higher", "lower", "primary"), pool.effectiveOrder())
        pool.pin("lower")
        assertEquals(listOf("lower", "higher", "primary"), pool.effectiveOrder())
        assertEquals("lower", pool.nextTargetLabel("session"))
        pool.unpin()
        pool.reset()
        assertEquals(listOf("higher", "lower", "primary"), pool.order)
        assertEquals("higher", pool.nextTargetLabel())
    }

    @Test
    fun `both quota windows advance to the next ordered account rather than the weekly favorite`() {
        for (weekly in listOf(false, true)) {
            val fixture = Fixture()
            fixture.pool.order = listOf("higher", "primary", "lower")
            assertEquals("higher", fixture.pool.nextTargetLabel())
            fixture.quota("higher", five = if (weekly) 0.0 else 100.0, seven = if (weekly) 100.0 else 0.0)
            assertEquals("primary", fixture.pool.nextTargetLabel())
            fixture.quota("primary", five = 100.0)
            assertEquals("lower", fixture.pool.nextTargetLabel())
            assertEquals("lower", (fixture.pool.select("session") as Selection.Chosen).account.account.label)
        }
    }

    @Test
    fun `invalid order leaves the previous immutable policy intact and empty restores defaults`() {
        val fixture = Fixture()
        val labels = mutableListOf("higher", "lower")
        fixture.pool.order = labels
        labels.clear()
        assertEquals(listOf("higher", "lower"), fixture.pool.order)
        assertThrows<IllegalArgumentException> { fixture.pool.order = listOf("unknown") }
        assertThrows<IllegalArgumentException> { fixture.pool.order = listOf("higher", "higher") }
        assertEquals(listOf("higher", "lower"), fixture.pool.order)
        fixture.pool.order = emptyList()
        assertEquals("higher", fixture.pool.nextTargetLabel())
    }

    @Test
    fun `default mode spends the more used account whose weekly reset comes first`() {
        val fixture = Fixture()
        fixture.quota("primary", sevenReset = 4_000L)
        fixture.quota("higher", seven = 80.0, sevenReset = 2_000L)
        fixture.quota("lower", seven = 1.0, sevenReset = 3_000L)
        assertEquals("higher", fixture.pool.nextTargetLabel())
        assertEquals("higher", (fixture.pool.select("session") as Selection.Chosen).account.account.label)
    }

    @Test
    fun `the earliest usable reset wins even when the weekly reset is later`() {
        val fixture = Fixture()
        fixture.quota("primary", fiveReset = 1_100L, sevenReset = 9_000L)
        fixture.quota("higher", fiveReset = 1_800L, sevenReset = 2_000L)
        fixture.quota("lower", fiveReset = 1_900L, sevenReset = 3_000L)

        assertEquals("primary", fixture.pool.nextTargetLabel())
        assertEquals("primary", (fixture.pool.select("synthetic-session") as Selection.Chosen).account.account.label)
    }

    @Test
    fun `default mode keeps a free current account after primary resets`() {
        val fixture = Fixture()
        fixture.quota("primary", five = 100.0, fiveReset = 1_100L, sevenReset = 1_500L)
        fixture.quota("higher", sevenReset = 2_000L)
        fixture.quota("lower", sevenReset = 3_000L)
        assertEquals("higher", (fixture.pool.select("session") as Selection.Chosen).account.account.label)
        fixture.at.set(1_200_000L)
        assertEquals("higher", fixture.pool.nextTargetLabel("session"))
        val kept = (fixture.pool.select("session") as Selection.Chosen).account
        assertEquals("higher", kept.account.label)
        assertEquals(null, kept.switch)
        fixture.quota("higher", five = 100.0, fiveReset = 2_000L)
        assertEquals("primary", fixture.pool.nextTargetLabel("session"))
        val moved = (fixture.pool.select("session") as Selection.Chosen).account
        assertEquals("primary", moved.account.label)
        assertEquals("quota usage reading full", moved.switch?.reason)
    }

    @Test
    fun `five hour reset breaks weekly ties and unknown quota comes last`() {
        val fixture = Fixture()
        fixture.quota("higher", fiveReset = 1_800L, sevenReset = 2_000L)
        fixture.quota("lower", fiveReset = 1_400L, sevenReset = 2_000L)
        assertEquals(listOf("lower", "higher", "primary"), fixture.pool.effectiveOrder())
        assertEquals("lower", fixture.pool.nextTargetLabel())
        assertEquals("lower", (fixture.pool.select("session") as Selection.Chosen).account.account.label)
    }

    @Test
    fun `observed quota without reset still ranks before unobserved primary`() {
        val fixture = Fixture()
        fixture.quota("higher", fiveReset = null, sevenReset = null)
        assertEquals(listOf("lower", "higher", "primary"), fixture.pool.effectiveOrder())
    }

    @Test
    fun `an elapsed five hour window cannot win a tie between current weekly resets`() {
        val fixture = Fixture()
        fixture.quota("higher", fiveReset = 1_800L, sevenReset = 2_000L)
        fixture.quota("lower", fiveReset = 999L, sevenReset = 2_000L)
        assertEquals(listOf("higher", "lower", "primary"), fixture.pool.effectiveOrder())
        assertEquals("higher", fixture.pool.nextTargetLabel())
    }

    @Test
    fun `a snapshot whose windows have both reset ranks with unknown quota last`() {
        val fixture = Fixture()
        fixture.quota("higher", fiveReset = 999L, sevenReset = 999L)
        assertEquals(listOf("lower", "primary", "higher"), fixture.pool.effectiveOrder())
    }

    @Test
    fun `an unavailable login cannot lend its reset ordering a known quota`() {
        val fixture = Fixture()
        fixture.quota("lower", fiveReset = 1_100L, sevenReset = 1_500L)
        fixture.pool.members.single { it.label == "lower" }.cooldown.markUnavailable(60_000L)
        assertEquals(listOf("higher", "primary", "lower"), fixture.pool.effectiveOrder())
        assertEquals("higher", fixture.pool.nextTargetLabel())
    }

    @Test
    fun `unknown quota on an unavailable primary cannot precede a selectable unknown login`() {
        val fixture = Fixture()
        fixture.pool.members = fixture.pool.members.map {
            it.copy(quota = AccountQuotaSource { QuotaSnapshot() })
        }
        fixture.pool.members.single { it.primary }.cooldown.markUnavailable(60_000L)
        assertEquals(listOf("higher", "lower", "primary"), fixture.pool.effectiveOrder())
    }

    private class Fixture {
        val at = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        private val quotas = mapOf(
            "primary" to AtomicReference(QuotaSnapshot()),
            "higher" to AtomicReference(QuotaSnapshot(sevenDay = QuotaWindow(80.0, 2000L, 604800L))),
            "lower" to AtomicReference(QuotaSnapshot(sevenDay = QuotaWindow(1.0, 2000L, 604800L))),
        )
        private val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic")
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        }
        val pool = AccountPool(
            quotas.map { (label, quota) ->
                PoolAccount(
                    label = label,
                    primary = label == "primary",
                    auth = auth,
                    quota = AccountQuotaSource(quota::get),
                    cooldown = RateLimitCooldown(ElapsedClock { 0L }),
                    credentialPresent = true,
                )
            },
            WallClock(at::get),
        )

        fun quota(
            label: String,
            five: Double = 0.0,
            seven: Double = 0.0,
            fiveReset: Long? = 2_000L,
            sevenReset: Long? = 2_000L,
        ) {
            quotas.getValue(label).set(
                QuotaSnapshot(
                    fiveHour = QuotaWindow(five, fiveReset, 18000L),
                    sevenDay = QuotaWindow(seven, sevenReset, 604800L),
                ),
            )
        }
    }
}

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
        assertEquals("primary", fixture.pool.nextTargetLabel())
    }

    private class Fixture {
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
            WallClock { 1000000L },
        )

        fun quota(label: String, five: Double = 0.0, seven: Double = 0.0) {
            quotas.getValue(label).set(
                QuotaSnapshot(
                    fiveHour = QuotaWindow(five, 2000L, 18000L),
                    sevenDay = QuotaWindow(seven, 2000L, 604800L),
                ),
            )
        }
    }
}

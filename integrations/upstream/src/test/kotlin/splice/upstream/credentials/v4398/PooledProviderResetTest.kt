package splice.upstream.credentials.v4398

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.upstream.credentials.AccountPoolTest

private const val THIRTY_SECONDS_MS = 30_000L
private const val NINETY_SECONDS_MS = 90_000L

class PooledProviderResetTest {
    @Test
    fun `a pool with every account cooling reads the earliest provider reset`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        primary.cooldown.markUnavailable(NINETY_SECONDS_MS)
        backup.cooldown.markUnavailable(THIRTY_SECONDS_MS)

        assertEquals(THIRTY_SECONDS_MS, pool.providerResetForMs)
    }

    @Test
    fun `a pool with any account free to try has no head-wide reset`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        primary.cooldown.markUnavailable(NINETY_SECONDS_MS)

        assertEquals(0L, pool.providerResetForMs)
    }

    @Test
    fun `a pool reads ready again once the cooling accounts recover`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary)
        primary.cooldown.markUnavailable(THIRTY_SECONDS_MS)
        fixture.advanceElapsed(NINETY_SECONDS_MS)

        assertEquals(0L, pool.providerResetForMs)
    }
}

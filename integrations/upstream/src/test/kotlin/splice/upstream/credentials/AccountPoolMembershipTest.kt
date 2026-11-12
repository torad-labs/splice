// NEW: publishing members never replaces a sticky session, cooldown owner or already leased account.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.retry.RateLimitCooldown

class AccountPoolMembershipTest {
    private fun account(label: String, primary: Boolean = false): PoolAccount = PoolAccount(
        label,
        primary,
        object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-$label")
            override suspend fun refresh(): Credentials? = null
            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        },
        AccountQuotaSource { null },
        RateLimitCooldown(ElapsedClock { 0L }),
    )

    private fun chosen(pool: AccountPool, session: String): AccountSelection =
        (pool.select(session) as? Selection.Chosen)?.account ?: error("expected a live choice")

    @Test
    fun `membership publication preserves sessions order cooldowns and already captured choices`() {
        val primary = account("primary", primary = true)
        val backup = account("backup")
        val fresh = account("fresh")
        val pool = AccountPool(listOf(primary, backup), WallClock { 1_000_000L })
        pool.order = listOf("backup", "primary")
        val held = chosen(pool, "sticky")
        assertSame(backup, held.account)
        val other = chosen(pool, "other")
        backup.cooldown.arm(10L)
        pool.members = listOf(primary, backup, fresh)
        assertEquals(listOf("backup", "primary"), pool.order)
        assertSame(backup, chosen(pool, "sticky").account)
        assertSame(backup.cooldown, pool.members.single { it.label == "backup" }.cooldown)
        assertSame(backup, held.account)
        assertEquals("backup", pool.view("other").selectedLabel)
        pool.members = listOf(primary, fresh)
        assertSame(primary, chosen(pool, "sticky").account)
        assertSame(backup, held.account)
        assertSame(backup, other.account)
        held.markTurnSucceeded()
        held.releaseCredentialProbe()
        assertFalse(pool.pin("backup"))
        assertEquals(listOf("primary"), pool.order)
    }
}

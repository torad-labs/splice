// NEW: publishing members never replaces a sticky session, cooldown owner or already leased account.
package splice.upstream.credentials

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.AccountBlock
import splice.core.failure.OwnerStep
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

    @Test
    fun `door filter evaluated after membershipRefresh never selects a newly added native login`() = runTest {
        val own = account("claude-code", primary = true)
        val added = account("acct-a")
        val native = account("native:claude", primary = true)
        val pool = AccountPool(listOf(own, added), WallClock { 1_000_000L })
        var refreshed = false
        pool.membershipRefresh = AccountMembershipRefresh {
            if (!refreshed) {
                refreshed = true
                pool.members = listOf(native, added)
            }
        }

        val doorAllowed = { label: String -> label != "claude-code" && !label.startsWith("native:") }
        val chosen = pool.awaitingEvidence.only(doorAllowed).select("sess-door") as Selection.Chosen

        assertEquals("acct-a", chosen.account.account.label, "newly added native:claude must never serve a door turn")

        // When no added login exists after refresh adds native:claude, the door turn is exhausted rather than using it.
        val emptyPool = AccountPool(listOf(own), WallClock { 1_000_000L })
        emptyPool.membershipRefresh = AccountMembershipRefresh {
            emptyPool.members = listOf(native)
        }
        val refused = emptyPool.awaitingEvidence.only(doorAllowed).select("sess-door-none")
        assertTrue(refused is Selection.Exhausted, "native:claude alone cannot satisfy a door turn: $refused")
    }

    @Test
    fun `door exhaustion reports reset and owner blocks only from door-eligible logins`() = runTest {
        var nowMs = 1_000_000L
        val clock = WallClock { nowMs }
        val native = account("native:claude", primary = true)
        val added = account("acct-a")
        val pool = AccountPool(listOf(native, added), clock)

        // native:claude has an earlier auth hold + owner block (until 1_300s); acct-a is auth-held later (until 1_400s).
        val nativeChoice = pool.select("sess-native") as Selection.Chosen
        nativeChoice.account.markCredentialUnavailable(AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "claude.ai"))
        nowMs = 1_100_000L
        val addedChoice = pool.select("sess-added") as Selection.Chosen
        assertEquals("acct-a", addedChoice.account.account.label)
        addedChoice.account.markCredentialUnavailable()

        val doorAllowed = { label: String -> label != "claude-code" && !label.startsWith("native:") }
        val refused = pool.awaitingEvidence.only(doorAllowed).select("sess-door") as Selection.Exhausted

        assertEquals(1_400L, refused.earliestResetEpochSeconds, "exhaustion ignores native:claude's earlier reset")
        assertEquals(null, refused.blocked, "exhaustion ignores native:claude's owner block")
    }
}

// NEW: V4-132 — the REAL pin behind POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual
// switch") and the read-only "next target" probe behind GET /api/accounts. Before this,
// AccountPool.select() was policy-only: even a session sitting on a deliberately chosen backup
// fell back to primary on its very next turn. These tests pin down that a pin now wins over
// primary, survives across turns, and falls back to ordinary policy when it names an unavailable
// or unknown account — never below the pre-pin behaviour AccountPoolTest.kt already covers.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.retry.RateLimitCooldown
import java.util.concurrent.atomic.AtomicReference

class AccountPoolPinTest {

    private fun AccountPool.chosen(sessionId: String?): AccountSelection =
        when (val selection = select(sessionId)) {
            is Selection.Chosen -> selection.account
            is Selection.Exhausted -> throw AssertionError("expected a chosen account, got exhausted")
        }

    @Test
    fun `an unknown label is refused and pins nothing`() {
        val fixture = Fixture()
        val pool = fixture.pool(fixture.account("primary", primary = true), fixture.account("plus-a"))

        assertFalse(pool.pin("no-such-label"))
        assertNull(pool.pinned())
    }

    @Test
    fun `a known label is pinned and reported back`() {
        val fixture = Fixture()
        val pool = fixture.pool(fixture.account("primary", primary = true), fixture.account("plus-a"))

        assertTrue(pool.pin("plus-a"))
        assertEquals("plus-a", pool.pinned())
    }

    @Test
    fun `a pin wins over primary on the very next turn, unlike plain policy`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        assertSame(primary, pool.chosen("session").account)

        pool.pin("plus-a")
        val pinned = pool.chosen("session")

        assertSame(backup, pinned.account)
        assertEquals("operator pinned this account", pinned.switch?.reason)
    }

    @Test
    fun `a changed caller login never overrides an explicit pin`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.select("session", emptySet(), "previous-login")
        pool.pin("plus-a")

        when (val selection = pool.select("session", emptySet(), "primary")) {
            is Selection.Chosen -> assertSame(backup, selection.account.account)
            is Selection.Exhausted -> throw AssertionError("a usable pin must remain selected after login")
        }
    }

    @Test
    fun `a pin stays sticky across turns, not just the one that observed the switch`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.pin("plus-a")
        pool.chosen("session")

        val second = pool.chosen("session")

        assertSame(backup, second.account)
        assertNull(second.switch, "the second turn on the same pin is not itself a NEW switch")
    }

    @Test
    fun `unpin restores default stickiness on the current free account`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.pin("plus-a")
        pool.chosen("session")

        pool.unpin()
        val restored = pool.chosen("session")

        assertSame(backup, restored.account)
        assertNull(restored.switch)
        assertNull(pool.pinned())
    }

    @Test
    fun `reset clears the pin along with the rest of runtime state`() {
        val fixture = Fixture()
        val pool = fixture.pool(fixture.account("primary", primary = true), fixture.account("plus-a"))
        pool.pin("plus-a")

        pool.reset()

        assertNull(pool.pinned())
    }

    @Test
    fun `a pin on an unavailable account falls through to ordinary policy, never wedging the head`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val held = fixture.account("plus-a")
        val pool = fixture.pool(primary, held)
        held.cooldown.markUnavailable(60_000L)

        pool.pin("plus-a")
        val chosen = pool.chosen("session")

        assertFalse(pool.view(null).accounts.single { it.label == "plus-a" }.available)
        assertSame(primary, chosen.account, "a provider-held pin must not refuse turns the head could otherwise serve")
    }

    @Test
    fun `nextTargetLabel probes the pin without acquiring a credential lease`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        pool.pin("plus-a")

        assertEquals("plus-a", pool.nextTargetLabel("session"))
        // The probe must not itself have chosen anything: the next REAL select() still sees the
        // same candidate order, not a session already latched to a different account by the probe.
        assertSame(backup, pool.chosen("session").account)
    }

    @Test
    fun `nextTargetLabel with no pin reports the same account choose would`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary, fixture.account("plus-a"))

        assertEquals("primary", pool.nextTargetLabel(null))
    }

    @Test
    fun `each account's view carries its window's own reported length, not a fixed weekly assumption`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        val account = pool.view(null).accounts.single { it.label == "plus-a" }

        assertEquals(18_000L, account.quota.fiveHour.windowSeconds)
        assertEquals(604_800L, account.quota.sevenDay.windowSeconds)
    }

    @Test
    fun `a session pin moves that session alone while another session keeps its account`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        assertSame(primary, pool.chosen("first").account)
        assertSame(primary, pool.chosen("second").account)

        assertTrue(pool.pin("plus-a", "first"))

        val pinned = pool.chosen("first")
        assertSame(backup, pinned.account)
        assertEquals("operator pinned this account", pinned.switch?.reason)
        assertSame(primary, pool.chosen("second").account, "a pin on one session never moves another")
        assertEquals("plus-a", pool.pinned("first"))
        assertNull(pool.pinned("second"))
        assertNull(pool.pinned(), "the head-wide pin is untouched")
    }

    @Test
    fun `a session pin outranks the head pin for that session alone, and dropping it returns to the head pin`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val plusA = fixture.account("plus-a")
        val plusB = fixture.account("plus-b")
        val pool = fixture.pool(primary, plusA, plusB)
        pool.pin("plus-a")
        pool.pin("plus-b", "own")

        assertSame(plusB, pool.chosen("own").account)
        assertSame(plusA, pool.chosen("other").account, "a session without its own pin follows the head's")

        pool.unpin("own")

        assertSame(plusA, pool.chosen("own").account)
        assertEquals("plus-a", pool.pinned(), "dropping a session pin leaves the head pin")
    }

    @Test
    fun `a session pin on an unavailable account falls back to policy for that session`() {
        val fixture = Fixture()
        val primary = fixture.account("primary", primary = true)
        val held = fixture.account("plus-a")
        held.cooldown.markUnavailable(60_000L)
        val pool = fixture.pool(primary, held)
        pool.pin("plus-a", "own")

        assertSame(primary, pool.chosen("own").account, "a pin never wedges a session onto an exhausted login")
    }

    private class Fixture {
        val now = AtomicReference(1_000_000L)
        private var elapsed = 0L

        fun pool(vararg accounts: PoolAccount): AccountPool = AccountPool(accounts.toList(), WallClock(now::get))

        fun account(label: String, primary: Boolean = false): PoolAccount {
            val quota = AtomicReference(quota())
            val auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("secret", "private-account-id")
                override suspend fun describe(): AuthDescription =
                    AuthDescription(true, "test", mapOf("token" to "***"))
                override suspend fun refresh(): Credentials = credentials()
                override fun observedCredentialKey(): String = label
            }
            return PoolAccount(
                label = label,
                primary = primary,
                auth = auth,
                quota = AccountQuotaSource(quota::get),
                cooldown = RateLimitCooldown(ElapsedClock { elapsed }),
                credentialPresent = true,
            )
        }

        fun quota(five: Double = 0.0, weekly: Double = 0.0, reset: Long? = 2_000L): QuotaSnapshot = QuotaSnapshot(
            fiveHour = QuotaWindow(five, reset, 18_000L),
            sevenDay = QuotaWindow(weekly, reset, 604_800L),
        )
    }
}

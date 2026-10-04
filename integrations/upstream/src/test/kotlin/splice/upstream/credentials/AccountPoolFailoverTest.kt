// NEW: failover within one provider (operator ruling, Oct 3, 3:44 PM CT: "A provider has two or more logins, and when
// one hits its limit the same command moves to the next in the order Accounts sets"). A login the provider holds on its
// plan, by a native 429 with plan headers or a held refusal a translated provider read from the body, stays held for
// the reset it named, and the next turn of the same session goes to the next login in the operator's order. When every
// login is held, the turn goes to the login whose reset is nearest, so the client gets that login's own refusal and
// splice adds no retry. A restart keeps the held login held.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.RateLimitReply
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path

private const val SESSION = "one-command"
private const val HOUR_S = 3_600L

class AccountPoolFailoverTest {

    private fun AccountPool.chosen(sessionId: String? = SESSION): String =
        when (val selection = select(sessionId)) {
            is Selection.Chosen -> selection.account.account.label
            is Selection.Exhausted -> throw AssertionError("expected a chosen login, got exhausted")
        }

    private fun wallSeconds(): Long = System.currentTimeMillis() / 1_000L

    private fun reply(label: String): RateLimitReply = RateLimitReply(
        """{"type":"error","error":{"type":"rate_limit_error","message":"limit reached for $label"}}""",
        mapOf("anthropic-ratelimit-unified-status" to listOf("rejected")),
    )

    /** What RetryRules.rateLimitFailure does with a native 429 whose headers name a spent plan window. */
    private fun limitNatively(account: PoolAccount, resetInSeconds: Long) {
        val cooldown = account.cooldown
        cooldown.rateLimitReply = reply(account.label)
        val planned = cooldown.planHold.hold(PlanLimit("five_hour", wallSeconds() + resetInSeconds)) {}
        cooldown.arm(checkNotNull(planned))
    }

    /** What it does with a translated provider's 429 whose body names the window: a held refusal, no native reply. */
    private fun holdRefusal(account: PoolAccount, resetInSeconds: Long) {
        val planned = account.cooldown.planHold.hold(PlanLimit("seven_day", wallSeconds() + resetInSeconds)) {}
        account.cooldown.arm(checkNotNull(planned))
    }

    @Test
    fun `a login held by a native 429 sends the same command's next turn to the next login in the order`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true)
        val two = fixture.account("two")
        val three = fixture.account("three")
        val pool = fixture.pool(one, two, three).also { it.order = listOf("one", "two", "three") }
        assertEquals("one", pool.chosen())

        limitNatively(one, resetInSeconds = 2 * HOUR_S)

        assertEquals("two", pool.chosen(), "the next turn of the same command")
        assertEquals("5-hour plan limit reached", pool.view(SESSION).lastSwitch?.reason)
        assertEquals("two", pool.nextTargetLabel(SESSION))
        fixture.advanceElapsed(MAX_RATE_LIMIT_COOLDOWN_MS + 1)
        assertEquals("two", pool.chosen(), "the horizon lifted; the plan the provider named is still spent")
        assertFalse(pool.view(SESSION).accounts.single { it.label == "one" }.available)
        assertEquals(reply("one"), one.cooldown.rateLimitReply, "the refused login stays held")
    }

    @Test
    fun `a held refusal on a translated provider moves the turn the same way`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true)
        val two = fixture.account("two")
        val three = fixture.account("three")
        val pool = fixture.pool(one, two, three).also { it.order = listOf("two", "three", "one") }
        assertEquals("two", pool.chosen())

        holdRefusal(two, resetInSeconds = 5 * 24 * HOUR_S)

        assertEquals("three", pool.chosen())
    }

    @Test
    fun `when every login is held the turn goes to the login whose reset is nearest, with its own refusal`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true)
        val two = fixture.account("two")
        val three = fixture.account("three")
        val pool = fixture.pool(one, two, three).also { it.order = listOf("one", "two", "three") }
        limitNatively(one, resetInSeconds = 3 * HOUR_S)
        limitNatively(two, resetInSeconds = 1 * HOUR_S)
        limitNatively(three, resetInSeconds = 2 * HOUR_S)

        val selection = pool.select(SESSION)

        assertTrue(selection is Selection.Chosen, "a held login is chosen; admission answers with its refusal")
        val chosen = (selection as Selection.Chosen).account.account
        assertSame(two, chosen)
        assertEquals(reply("two"), chosen.cooldown.rateLimitReply, "the native refusal the client gets")
        assertTrue(chosen.cooldown.remainingMs() > 0L, "armed: the refusal is replayed with no upstream request")
        assertEquals("two", pool.nextTargetLabel(SESSION))
        fixture.advanceElapsed(MAX_RATE_LIMIT_COOLDOWN_MS + 1)
        assertEquals("two", pool.chosen(), "once the horizon lifts, the nearest reset is the one probed")
    }

    @Test
    fun `a restart keeps the held login held`(@TempDir dir: Path) {
        fun logins(): List<PoolAccount> = listOf("one", "two", "three").map { label ->
            PoolAccount(
                label = label,
                primary = label == "one",
                auth = SyntheticAuth,
                quota = AccountQuotaSource { null },
                cooldown = RateLimitCooldown(
                    ElapsedClock { 0L },
                    store = FileProviderHoldStore(dir.resolve("$label-provider-hold.json")) {},
                ),
            )
        }
        fun pool(accounts: List<PoolAccount>): AccountPool =
            AccountPool(accounts, WallClock { System.currentTimeMillis() })
                .also { it.order = listOf("one", "two", "three") }

        val before = logins()
        limitNatively(before.first(), resetInSeconds = 2 * HOUR_S)
        assertEquals("two", pool(before).chosen())

        val after = logins()

        assertEquals("two", pool(after).chosen(), "the held login is still held after the restart")
        assertEquals(reply("one"), after.first().cooldown.rateLimitReply)
    }

    private object SyntheticAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    }
}

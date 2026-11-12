// NEW: failover within one provider (operator ruling, Oct 3, 3:44 PM CT: "A provider has two or more logins, and when
// one hits its limit the same command moves to the next in the order Accounts sets"). A login the provider holds on its
// plan, by a native 429 with plan headers or a held refusal a translated provider read from the body, stays held for
// the reset it named, and the next turn of the same session goes to the next login in the operator's order. When every
// login is held and armed, selection chooses the nearest-reset eligible login. Its own provider answer controls the
// turn's hold; admission does not synthesize a quota refusal. A restart keeps the held login held.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.ModelQuota
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaSnapshot
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.NativeReply
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path

private const val SESSION = "one-command"
private const val HOUR_S = 3_600L
private const val FABLE = "claude-fable-5-2"

class AccountPoolFailoverTest {

    private fun AccountPool.chosen(sessionId: String? = SESSION): String =
        when (val selection = select(sessionId)) {
            is Selection.Chosen -> selection.account.account.label
            is Selection.Exhausted -> throw AssertionError("expected a chosen login, got exhausted")
        }

    private fun wallSeconds(): Long = System.currentTimeMillis() / 1_000L

    /** The login a new session's turn of [model] is sent to. */
    private fun AccountPool.chosenFor(model: String, sessionId: String): String =
        when (val selection = select(sessionId, emptySet(), null, model)) {
            is Selection.Chosen -> selection.account.account.label
            is Selection.Exhausted -> throw AssertionError("expected a chosen login, got exhausted")
        }

    /** A reading whose Fable week reads [percent], taken at [at]: each is its own, as the provider gives them. */
    private fun AccountPoolTest.Fixture.fableAt(percent: Double, at: Long): QuotaSnapshot =
        quota().copy(models = listOf(ModelQuota("Fable", percent)), updatedAt = at)

    /** Anthropic's refusal of a Fable turn on [account]: that model's week, spent until tomorrow. */
    private fun spendFable(account: PoolAccount) {
        val week = PlanLimit("seven_day_overage_included", wallSeconds() + 24 * HOUR_S, model = FABLE)
        account.cooldown.planHold.hold(week) {}
    }

    private fun reply(label: String): NativeReply = NativeReply(
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
    fun `the login named as next is one that serves the model the command's turns ask for`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true)
        val two = fixture.account("two")
        val pool = fixture.pool(one, two).also { it.order = listOf("one", "two") }
        val week = PlanLimit("seven_day_overage_included", wallSeconds() + 24 * HOUR_S, model = "fable")
        one.cooldown.planHold.hold(week) {}
        assertEquals("one", pool.nextTargetLabel(model = pool.asked.usual()), "no turn has named a model yet")

        pool.select(SESSION, emptySet(), null, "fable")

        assertEquals("two", pool.nextTargetLabel(model = pool.asked.usual()), "one's week for that model is used up")
        assertEquals("one", pool.nextTargetLabel(model = "haiku"), "and it still serves another model")
    }

    @Test
    fun `a login reset at its provider takes the model's turns again before the reset its refusal named`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true, quota = fixture.fableAt(100.0, at = 1L))
        val two = fixture.account("two")
        val pool = fixture.pool(one, two).also { it.order = listOf("one", "two") }
        spendFable(one)
        assertEquals("two", pool.chosenFor(FABLE, "first"), "one's Fable week is used up until tomorrow")
        val heard = mutableListOf<String>()
        pool.onEarlyReset = EarlyResetHeard { label, ended -> heard += "$label: $ended" }
        val joinsBefore = pool.joins

        // The person resets the login at the provider: its next reading shows the week at nothing.
        fixture.setQuota(one, fixture.fableAt(0.0, at = 2L))

        assertEquals("one", pool.chosenFor(FABLE, "second"), "the reading shows the week reset, so the hold ended")
        assertEquals(listOf("one: [$FABLE]"), heard, "and the journal is told which login and which window")
        assertEquals(joinsBefore + 1, pool.joins, "and every waiting turn is asked to move")
    }

    @Test
    fun `a week that read below full before its refusal stays held while it reads the same`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true, quota = fixture.fableAt(99.0, at = 1L))
        val two = fixture.account("two")
        val pool = fixture.pool(one, two).also { it.order = listOf("one", "two") }
        assertEquals("one", pool.chosenFor(FABLE, "first"), "the reading is a few minutes old and shows room")
        spendFable(one)

        // An answer for another model on the same login carries the same Fable figure in a newer reading.
        fixture.setQuota(one, fixture.fableAt(99.0, at = 2L))

        assertEquals("two", pool.chosenFor(FABLE, "second"), "the figure did not fall, so the refusal still stands")
    }

    @Test
    fun `a login whose whole week reads reset comes back for every model`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true, quota = fixture.quota(weekly = 100.0))
        val two = fixture.account("two")
        val pool = fixture.pool(one, two).also { it.order = listOf("one", "two") }
        holdRefusal(one, 24 * HOUR_S)
        assertEquals("two", pool.chosen("first"), "one's week is used up until tomorrow")

        fixture.setQuota(one, fixture.quota(weekly = 0.0))

        assertEquals("one", pool.chosen("second"), "its week reads reset")
    }

    @Test
    fun `the account named as following is the one the command moves to when the one in use is held`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true)
        val two = fixture.account("two")
        val three = fixture.account("three")
        val pool = fixture.pool(one, two, three).also { it.order = listOf("one", "three", "two") }
        assertEquals("one", pool.nextTargetLabel(SESSION))
        assertEquals("three", pool.view(SESSION).followingLabel)

        limitNatively(one, resetInSeconds = 2 * HOUR_S)
        assertEquals("three", pool.chosen(), "the turn goes where Accounts said it would")
        assertEquals("two", pool.view(SESSION).followingLabel)

        holdRefusal(three, resetInSeconds = 3 * HOUR_S)
        holdRefusal(two, resetInSeconds = 4 * HOUR_S)
        assertEquals("one", pool.nextTargetLabel(SESSION), "every login held: the nearest reset serves")
        assertEquals("three", pool.view(SESSION).followingLabel, "then the next nearest")
    }

    @Test
    fun `a lone login has no following account`() {
        val fixture = AccountPoolTest.Fixture()
        val pool = fixture.pool(fixture.account("only", primary = true))
        assertEquals("only", pool.nextTargetLabel(SESSION))
        assertEquals(null, pool.view(SESSION).followingLabel)
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
    fun `when every login is held the nearest reset is selected without clearing its provider hold`() {
        val fixture = AccountPoolTest.Fixture()
        val one = fixture.account("one", primary = true)
        val two = fixture.account("two")
        val three = fixture.account("three")
        val pool = fixture.pool(one, two, three).also { it.order = listOf("one", "two", "three") }
        limitNatively(one, resetInSeconds = 3 * HOUR_S)
        limitNatively(two, resetInSeconds = 1 * HOUR_S)
        limitNatively(three, resetInSeconds = 2 * HOUR_S)

        val selection = pool.select(SESSION)

        assertTrue(selection is Selection.Chosen, "an eligible login's provider answer controls the hold")
        assertEquals("two", (selection as Selection.Chosen).account.account.label)
        val horizons = pool.view(SESSION).blockedUntilEpochSecondsByLabel
        assertEquals(3, horizons.size, "all three provider holds remain published")
        assertEquals(horizons.values.minOrNull(), horizons["two"])
        assertTrue(pool.view(SESSION).accounts.none { it.available }, "selection does not clear a provider hold")
        assertEquals(reply("two"), two.cooldown.rateLimitReply)
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

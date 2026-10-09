// NEW: replaces the kt-quota-tracker-single-resolution wall. The decision "which QuotaTracker does this turn read" is now
// owned by the type system: HeadQuota keeps its trackers private and hands out only TurnQuota, so no site can re-derive the
// chain. What the type cannot say is the precedence itself, and that is pinned here: the SELECTED account's tracker beats the
// head's primary, and the primary answers only when no account is selected or none is tracked. A resolver that put the primary
// first would stamp the wrong account's anthropic-ratelimit-* headers on the client, and the first case below fails on it.
package splice.head.admission

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path

class TurnQuotaTest {
    @TempDir
    lateinit var tmp: Path

    private val session = "session-1"

    private fun tracker(name: String) = QuotaTracker(tmp.resolve("$name-quota.json"))

    private fun member(label: String, primary: Boolean, tracker: QuotaTracker) = PoolAccount(
        label = label,
        primary = primary,
        auth = Rig,
        quota = AccountQuotaSource(tracker::snapshot),
        cooldown = RateLimitCooldown(ProcessElapsedNow()),
        credentialPresent = true,
    )

    private fun pool(primary: QuotaTracker, backup: QuotaTracker) = AccountPool(
        listOf(member("primary", true, primary), member("backup", false, backup)),
        WallClock(System::currentTimeMillis),
    )

    @Test
    fun `the selected account's tracker wins over the head's primary`() {
        val primary = tracker("primary")
        val backup = tracker("backup")
        val pool = pool(primary, backup)
        pool.members.first { it.label == "primary" }.cooldown.markUnavailable(60_000L)
        pool.select(session)
        val quota = TurnQuota(pool, mapOf("primary" to primary, "backup" to backup), primary)
        assertSame(backup, quota.forSession(session, null), "the pool selected backup, so backup's tracker answers")
    }

    @Test
    fun `the primary answers when no pool is wired`() {
        val primary = tracker("primary")
        assertSame(primary, TurnQuota(null, emptyMap(), primary).forSession(session, null))
    }

    @Test
    fun `the primary answers when the selected account has no tracker`() {
        val primary = tracker("primary")
        val backup = tracker("backup")
        val pool = pool(primary, backup)
        pool.members.first { it.label == "primary" }.cooldown.markUnavailable(60_000L)
        pool.select(session)
        assertSame(primary, TurnQuota(pool, mapOf("primary" to primary), primary).forSession(session, null))
    }

    @Test
    fun `no tracker anywhere answers null`() {
        assertNull(TurnQuota(null, emptyMap(), null).forSession(session, null))
    }

    private object Rig : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("token", "id")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
    }
}

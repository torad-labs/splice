// NEW: V4-418 — a head reads the provider's own CURRENT quota reading when it names a window fully used, before
// any turn has been refused. Marlin (f7f1e9308): claudex said nothing while its poll said the week was 100% with a
// reset days out. V4-452 ruled what the reading IS: a reading, not a refusal. On Oct 1 claudex served 1,163 turns at a
// week read 100%, so HeadServer.quotaFull carries it beside a head that stays ready, and providerResetForMs, the one
// value any surface prints as out of quota, stays the held refusal's alone. A window that is not full, a reading
// older than QuotaFreshness's 15 minutes and a window past its reset read nothing: a stale figure says nothing of now.
package splice.head.v4418

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaFull
import splice.core.usage.QuotaFullWindow
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.head.noQuota
import splice.head.quotaFor
import splice.head.usage.QuotaTracker
import splice.upstream.ProviderTuning
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val NOW_MS = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()
private const val SECOND_MS = 1_000L
private const val TWO_HOURS_S = 2L * 3_600
private const val SIX_DAYS_S = 6L * 24 * 3_600
private const val FIVE_HOUR_S = 5L * 3_600
private const val SEVEN_DAY_S = 7L * 24 * 3_600
private const val READ_THREE_MINUTES_AGO_S = 3L * 60
private const val READ_SIXTEEN_MINUTES_AGO_S = 16L * 60

private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class SpentReadingResetTest {
    private val elapsed = AtomicLong(0L)
    private val clock = WallClock { NOW_MS + elapsed.get() }

    private fun window(used: Double, resetsInSeconds: Long?, length: Long) =
        QuotaWindow(used, resetsInSeconds?.let { NOW_MS / SECOND_MS + it }, length)

    private fun week(used: Double, resetsInSeconds: Long? = SIX_DAYS_S) = window(used, resetsInSeconds, SEVEN_DAY_S)

    private fun fiveHour(used: Double, resetsInSeconds: Long? = TWO_HOURS_S) =
        window(used, resetsInSeconds, FIVE_HOUR_S)

    private fun reading(
        five: QuotaWindow? = null,
        seven: QuotaWindow? = null,
        readSecondsAgo: Long = READ_THREE_MINUTES_AGO_S,
    ) = QuotaSnapshot(five, seven, "plus", NOW_MS - readSecondsAgo * SECOND_MS)

    private fun tracker(tmp: Path, name: String, snapshot: QuotaSnapshot): QuotaTracker =
        QuotaTracker(tmp.resolve("$name.json"), clock, log = { }).also { it.record(snapshot) }

    private fun head(tmp: Path, quota: HeadDeps.HeadQuota): HeadServer = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = FakeAuth(),
                baseUrl = "http://127.0.0.1:1",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        listenPort = 0,
        deps = headDeps(tmp = tmp).copy(quotaBundle = quota),
    )

    private fun single(tmp: Path, snapshot: QuotaSnapshot, tag: String = "") =
        head(tmp, quotaFor(tracker(tmp, "primary$tag", snapshot), null))

    private fun account(label: String, primary: Boolean, tracker: QuotaTracker) = PoolAccount(
        label = label,
        primary = primary,
        auth = FakeAuth(),
        quota = AccountQuotaSource(tracker::snapshot),
        cooldown = RateLimitCooldown(ProcessElapsedNow()),
    )

    private fun pooled(tmp: Path, primary: QuotaSnapshot, backup: QuotaSnapshot, tag: String): HeadServer {
        val first = tracker(tmp, "primary$tag", primary)
        val second = tracker(tmp, "backup$tag", backup)
        val pool = AccountPool(listOf(account("primary", true, first), account("backup", false, second)), clock)
        return head(tmp, quotaFor(first, pool, mapOf("primary" to first, "backup" to second)))
    }

    private fun fullWeek(resetsInSeconds: Long = SIX_DAYS_S) =
        QuotaFull(QuotaFullWindow.SEVEN_DAY, NOW_MS / SECOND_MS + resetsInSeconds)

    @Test
    fun `a current week at 100 percent reads full until its reset, with no turn ever refused`(@TempDir tmp: Path) {
        assertEquals(fullWeek(), single(tmp, reading(seven = week(100.0))).quotaFull())
    }

    @Test
    fun `a full reading is not a refusal`(@TempDir tmp: Path) {
        // claudex served turns all day at a week read 100%: only a refusal the head holds is out of quota.
        assertEquals(0L, single(tmp, reading(seven = week(100.0))).providerResetForMs())
    }

    @Test
    fun `a week at 99 percent reads nothing`(@TempDir tmp: Path) {
        assertNull(single(tmp, reading(seven = week(99.0))).quotaFull())
    }

    @Test
    fun `a full reading older than the freshness window reads nothing`(@TempDir tmp: Path) {
        val stale = reading(seven = week(100.0), readSecondsAgo = READ_SIXTEEN_MINUTES_AGO_S)

        assertNull(single(tmp, stale).quotaFull())
    }

    @Test
    fun `a full window whose reset has passed reads nothing`(@TempDir tmp: Path) {
        assertNull(single(tmp, reading(seven = week(100.0, resetsInSeconds = -60L))).quotaFull())
    }

    @Test
    fun `the reading holds its reset as the clock moves, and drops out when the week resets`(@TempDir tmp: Path) {
        val head = single(tmp, reading(seven = week(100.0, resetsInSeconds = TWO_HOURS_S)))
        assertEquals(fullWeek(TWO_HOURS_S), head.quotaFull())

        elapsed.set(SECOND_MS * 60)
        assertEquals(fullWeek(TWO_HOURS_S), head.quotaFull())

        elapsed.set(TWO_HOURS_S * SECOND_MS)
        assertNull(head.quotaFull())
    }

    @Test
    fun `a full five-hour window alone is named, and both full name the later reset`(@TempDir tmp: Path) {
        val five = single(tmp, reading(five = fiveHour(100.0), seven = week(40.0)), tag = "-five")
        assertEquals(QuotaFull(QuotaFullWindow.FIVE_HOUR, NOW_MS / SECOND_MS + TWO_HOURS_S), five.quotaFull())

        val both = single(tmp, reading(five = fiveHour(100.0), seven = week(100.0)), tag = "-both")
        assertEquals(fullWeek(), both.quotaFull())
    }

    @Test
    fun `a full window that names no reset names nothing`(@TempDir tmp: Path) {
        assertNull(single(tmp, reading(seven = week(100.0, resetsInSeconds = null))).quotaFull())
    }

    @Test
    fun `a head with no quota tracker reads nothing`(@TempDir tmp: Path) {
        assertNull(head(tmp, noQuota()).quotaFull())
    }

    @Test
    fun `a pool reads full only when every account does, with the earliest of their resets`(@TempDir tmp: Path) {
        val spent = reading(seven = week(100.0))
        val sooner = reading(seven = week(100.0, resetsInSeconds = TWO_HOURS_S))
        val free = reading(seven = week(20.0))

        assertEquals(fullWeek(TWO_HOURS_S), pooled(tmp, spent, sooner, "-all").quotaFull())
        assertNull(pooled(tmp, spent, free, "-one").quotaFull())
    }
}

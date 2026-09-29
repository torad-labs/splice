// NEW: V4-418 — a head reads "out of quota until <reset>" from the provider's own CURRENT quota reading, before any turn
// has been refused. Marlin (f7f1e9308): claudex read ready while its poll said the week was 100% with a reset days out,
// because V4-398 and V4-412 learn a hold only from a refused turn. The reading is the provider saying what a refusal says.
// Driven through HeadServer.providerResetForMs, the one value status, /health and usage all read. A window that is not
// full, a reading older than QuotaFreshness's 15 minutes and a window past its reset must read ready: a stale figure is
// not a statement about now.
package splice.head.v4418

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
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
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        ),
        listenPort = 0,
        deps = headDeps(tmp = tmp, quota = quota),
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

    @Test
    fun `a current week at 100 percent reads out of quota until its reset, with no turn ever refused`(
        @TempDir tmp: Path,
    ) {
        val remaining = single(tmp, reading(seven = week(100.0))).providerResetForMs()

        assertEquals(SIX_DAYS_S * SECOND_MS, remaining)
    }

    @Test
    fun `a week at 99 percent reads ready`(@TempDir tmp: Path) {
        assertEquals(0L, single(tmp, reading(seven = week(99.0))).providerResetForMs())
    }

    @Test
    fun `a full reading older than the freshness window reads ready`(@TempDir tmp: Path) {
        val stale = reading(seven = week(100.0), readSecondsAgo = READ_SIXTEEN_MINUTES_AGO_S)

        assertEquals(0L, single(tmp, stale).providerResetForMs())
    }

    @Test
    fun `a full window whose reset has passed reads ready`(@TempDir tmp: Path) {
        assertEquals(0L, single(tmp, reading(seven = week(100.0, resetsInSeconds = -60L))).providerResetForMs())
    }

    @Test
    fun `the deadline moves down the week as the clock does, and drops out when the week resets`(@TempDir tmp: Path) {
        val head = single(tmp, reading(seven = week(100.0, resetsInSeconds = TWO_HOURS_S)))
        assertEquals(TWO_HOURS_S * SECOND_MS, head.providerResetForMs())

        elapsed.set(SECOND_MS * 60)
        assertEquals((TWO_HOURS_S - 60L) * SECOND_MS, head.providerResetForMs())

        elapsed.set(TWO_HOURS_S * SECOND_MS)
        assertEquals(0L, head.providerResetForMs())
    }

    @Test
    fun `a spent five-hour window alone reads out until it resets, and both spent read the later reset`(
        @TempDir tmp: Path,
    ) {
        val five = single(tmp, reading(five = fiveHour(100.0), seven = week(40.0)), tag = "-five")
        assertEquals(TWO_HOURS_S * SECOND_MS, five.providerResetForMs())

        val both = single(tmp, reading(five = fiveHour(100.0), seven = week(100.0)), tag = "-both")
        assertEquals(SIX_DAYS_S * SECOND_MS, both.providerResetForMs())
    }

    @Test
    fun `a spent window that names no reset names no instant`(@TempDir tmp: Path) {
        assertEquals(0L, single(tmp, reading(seven = week(100.0, resetsInSeconds = null))).providerResetForMs())
    }

    @Test
    fun `a head with no quota tracker reads ready`(@TempDir tmp: Path) {
        assertEquals(0L, head(tmp, noQuota()).providerResetForMs())
    }

    @Test
    fun `a pool reads out only when every account is spent, until the earliest of their resets`(@TempDir tmp: Path) {
        val spent = reading(seven = week(100.0))
        val sooner = reading(seven = week(100.0, resetsInSeconds = TWO_HOURS_S))
        val free = reading(seven = week(20.0))

        assertEquals(TWO_HOURS_S * SECOND_MS, pooled(tmp, spent, sooner, "-all").providerResetForMs())
        assertEquals(0L, pooled(tmp, spent, free, "-one").providerResetForMs())
    }
}

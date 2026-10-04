package splice.upstream.v4412

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.RetryNotice
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RateLimitTurn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val SIX_DAYS_S = 6L * 24 * 3_600
private const val MS = 1_000L
private val BOOT = Instant.parse("2026-09-29T01:15:00Z").toEpochMilli()

/** V4-412: a provider's word about when it stops refusing survives the restart the operator does on
 *  every green jar. A cooldown rebuilt from the same file (a fresh elapsed clock, as after a restart)
 *  still reports the provider's reset and the plan window, so status reads out of quota with no turn
 *  in between; what gates turns (the horizon) is not stored, so the first turn still probes upstream. */
class ProviderHoldRestartTest {
    private val wall = BOOT
    private val logs = mutableListOf<String>()

    private fun boot(dir: Path, wallMs: Long = wall) = RateLimitCooldown(
        clock = ElapsedClock { 0L },
        wallClock = WallClock { wallMs },
        store = FileProviderHoldStore(dir.resolve("hold.json"), LogSink { logs += it }),
    )

    private fun weeklyBody(resetAt: Long) =
        """{"error":{"type":"usage_limit_reached","plan_type":"pro","resets_at":$resetAt}}"""

    private fun RateLimitCooldown.refused(body: String) = rateLimitedPlan(
        pushbackMs = null,
        turn = RateLimitTurn(this, pooledAccount = false),
        canRetry = false,
        onRetry = RetryNotice {},
        nextRefreshed = false,
        body = body,
    )

    @Test
    fun `the provider reset survives a restart and reads out of quota with no turn`(@TempDir dir: Path) {
        val resetAt = wall / MS + SIX_DAYS_S
        boot(dir).refused(weeklyBody(resetAt))

        val restarted = boot(dir)

        assertEquals(resetAt * MS - wall, restarted.providerUnavailableForMs())
        assertEquals(0L, restarted.remainingMs(), "the gating horizon is not stored: the first turn probes")
    }

    @Test
    fun `the plan hold survives a restart and the restart's clear`(@TempDir dir: Path) {
        val limit = PlanLimit("seven_day", wall / MS + SIX_DAYS_S)
        boot(dir).planHold.hold(limit, RetryNotice {})

        val restarted = boot(dir)
        restarted.clear()
        restarted.clearUnavailable()

        assertEquals(limit, restarted.planHold.live())
        assertEquals(limit.resetEpochSeconds * MS - wall, restarted.planHold.forMs())
        val notices = mutableListOf<String>()
        restarted.failFastIfArmed(RetryNotice(notices::add))
        assertTrue(notices.single().startsWith("plan hold: probing upstream"), notices.toString())
    }

    @Test
    fun `a pooled account's selection cooldown clears on restart but its provider reset does not`(@TempDir dir: Path) {
        boot(dir).markUnavailable(SIX_DAYS_S * MS)

        val restarted = boot(dir)
        restarted.clearUnavailable()

        assertEquals(SIX_DAYS_S * MS, restarted.providerUnavailableForMs())
        assertEquals(0L, restarted.unavailableForMs())
    }

    @Test
    fun `holds whose instant has passed load as none and leave no file`(@TempDir dir: Path) {
        val resetAt = wall / MS + 600
        val first = boot(dir)
        first.refused(weeklyBody(resetAt))
        first.planHold.hold(PlanLimit("seven_day", resetAt), RetryNotice {})

        val later = boot(dir, wallMs = (resetAt + 1) * MS)

        assertEquals(0L, later.providerUnavailableForMs())
        assertNull(later.planHold.live())
        assertFalse(Files.exists(dir.resolve("hold.json")), "a passed statement is not kept")
    }

    @Test
    fun `a corrupt file loads as none with one diagnostic that leaks nothing, a missing one says nothing`(
        @TempDir dir: Path,
    ) {
        boot(dir)
        assertTrue(logs.isEmpty(), "a first boot has no file and no diagnostic: $logs")
        Files.writeString(dir.resolve("hold.json"), """{"reset_at_epoch_seconds": SECRET-not-json""")

        val restarted = boot(dir)

        assertEquals(0L, restarted.providerUnavailableForMs())
        assertNull(restarted.planHold.live())
        assertEquals(1, logs.size, logs.toString())
        assertFalse(logs.single().contains("SECRET"), "the diagnostic names the path, never the content")
    }

    @Test
    fun `an answered turn ends both statements on disk as well as in memory`(@TempDir dir: Path) {
        val resetAt = wall / MS + SIX_DAYS_S
        val first = boot(dir)
        first.refused(weeklyBody(resetAt))
        first.planHold.hold(PlanLimit("seven_day", resetAt), RetryNotice {})
        assertTrue(Files.exists(dir.resolve("hold.json")))

        first.answered()

        assertEquals(0L, first.providerUnavailableForMs())
        assertNull(first.planHold.live())
        assertFalse(Files.exists(dir.resolve("hold.json")))
        assertEquals(0L, boot(dir).providerUnavailableForMs())
    }

    @Test
    fun `an answered turn on a head with nothing held writes nothing`(@TempDir dir: Path) {
        boot(dir).answered()

        assertFalse(Files.exists(dir.resolve("hold.json")))
        assertTrue(logs.isEmpty(), logs.toString())
    }
}

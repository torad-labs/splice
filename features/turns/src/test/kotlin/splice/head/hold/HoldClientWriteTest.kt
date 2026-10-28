// NEW: failure-handling slice 5 (2026-10-27). A held turn ends at once when its client is gone (final-design.md
// section 9). The client-write-loss diagnosis of Oct 27 found that a frame write which fails only marks the client
// gone and rethrows, while a failed ping cancels the turn; a client lost while the hold writes its own line would then
// be noticed at the next ping, 30 s on, if the hold went on to wait. This fails the write at that chosen point, the
// hold's line, and asserts the hold never begins to wait.
package splice.head.hold

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.WatchdogBudget
import splice.core.util.WallClock
import splice.head.hold.resources.HoldSurfaces
import splice.head.hold.resources.SessionWall
import splice.head.hold.resources.TurnHold
import splice.head.testVoice
import splice.head.turn.LiveTurns
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.upstream.Waiter
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.Probes
import splice.upstream.retry.TurnWatchdog
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Where the wall clock starts: Oct 26, 2026, 12:00 UTC. */
private val START: Instant = Instant.parse("2026-10-26T12:00:00Z")

@OptIn(ExperimentalCoroutinesApi::class)
class HoldClientWriteTest {
    @Test
    fun `a hold whose line cannot reach its client ends at once, before it waits`() = runTest {
        val emitter = SseEmitterFactory().create(
            // The client is gone by the time the hold's line is written: that write fails as a lost socket does.
            write = { frame -> if ("[splice]" in frame) throw IOException("client gone") },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            streaming = StreamWiring(messageId = "msg_fixed"),
            voice = testVoice,
        )
        val slot = (InflightGate(LiveLimit { 1 }).acquire() as InflightGate.Admission.Acquired).slot
        val registry = HoldRegistry()
        val wall = WallClock { START.toEpochMilli() + currentTime }
        val surfaces = HoldSurfaces(
            emitter,
            slot,
            TurnWatchdog(WatchdogBudget(60.seconds, 60.seconds, 600.seconds)),
            LiveTurns(),
            CompletableDeferred(),
            lease = null,
        )
        val hold = TurnHold(
            HeldTurn(registry, Waiter { ms -> delay(ms) }, wall),
            surfaces,
            SessionWall({ currentTime }, Long.MAX_VALUE),
            wall,
        ) { 0L }
        val reset = START.plusSeconds(3_600)
        val limit = FailureCause.Provider.Limited(Lift.At(reset, true, LiftSource.RESET_HEADER), LimitWindow.FIVE_HOUR)
        val ask = HoldAsk(limit, Position(Moment.BEFORE_COMMIT, false), reset, Probes.FIRM, "login-a", wall())

        val ended = runCatching { hold.hold(ask) }

        assertTrue(ended.exceptionOrNull() is IOException, "the lost client ends the hold: $ended")
        assertEquals(0L, currentTime, "no time passed: the hold never waited for its reset")
        assertEquals(0, registry.tryNow(), "no turn is left held on the login")
    }
}

// PORT-OF: splice/gateway/head/TurnDriver.kt (collect) @ 86f1411 — invariants unchanged: the
// non-stream sibling of TurnDriver.stream. This is the campaign's own pre-priced contingency
// (dev/campaigns/head-decoupling.toml HD-24): the un-split TurnDriver.kt measured 1.83, just over
// the 1.8 gate, so collect's 18 lines move here exactly as pre-priced. [driver] is held (not a
// lambda — kt-no-lambda-seam) so [TurnDriver.driveSealingCancellation] stays the ONE copy of the
// L3 seal contract shared by stream and collect; the visibility widening (private -> internal) is
// named on that method.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import splice.core.perf.TurnPerf
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.CollectingTerminal
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.TurnWiring
import splice.spi.BuiltTurn
import splice.spi.InflightGate
import splice.spi.Provider
import java.util.concurrent.atomic.AtomicBoolean

internal class CollectTurn(
    private val provider: Provider,
    private val driveFactory: TurnDriveFactory,
    private val wiring: TurnWiring,
    private val driver: TurnDriver,
) {
    /** Non-stream sibling of TurnDriver.stream: Claude Code sends stream:false on some internal
     *  calls (the Node predecessor served them by collecting the terminal object). Drives the SAME
     *  fold/translator/honesty machinery into a [CollectingTerminal], then writes ONE Anthropic
     *  Messages JSON body — no SSE channel, no liveness pinger. */
    suspend fun collect(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        val terminal = CollectingTerminal(
            built.meta.originalModel,
            wiring.usagePayloadBuilder(provider.catalog, built.meta),
        )
        // Inert channel: the collect path never writes SSE frames, but postRound reads clientGone
        // (stays false — a buffered client can't be observed gone mid-turn) and the drive needs one.
        val channel = ClientChannel(
            coalesced = ImmediateSseWriter(writeRaw = {}, flushRaw = {}),
            writeMutex = Mutex(),
            clientGone = AtomicBoolean(false),
        )
        val drive = driveFactory.assembleDrive(TurnInputs(built, slot, t0, perf), terminal, channel)
        // collect never commits a 200 before its terminal respondText — a cancelled collect is a
        // native connection abort client-side, and sealing there only wrote an error body nobody
        // reads while polluting localOriginErrors (review 2026-07-22 round 3).
        //
        // pingClient = false is a MEASURED LIMITATION, not a claim that nothing needs to notice a
        // departed client (PR 99 settled this; HeadServerCollectDisconnectTest is the experiment).
        // A raw client socket closed mid-hold, with a 600s watchdog so it could not be the one
        // freeing anything: the stream:true control got its gate slot back in ~2s (keepalive write
        // fails -> clientGone -> turn cancelled), the identical stream:false request still held its
        // slot 20s later. Ktor does NOT cancel the call coroutine here even though the response is
        // wholly uncommitted, so an abandoned collect turn pins its slot and burns vendor quota
        // until TurnWatchdog's total cap, and the `clientGone` above stays false forever, which is
        // also why ClientAbandoned is unreachable for collect turns. Both are the SAME gap — a
        // collect-path liveness source — and closing it is its own change with its own review.
        driver.driveSealingCancellation(drive, pingClient = false, seal = false)
        call.respondText(
            terminal.responseBody().toString(),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(terminal.httpStatus()),
        )
    }
}

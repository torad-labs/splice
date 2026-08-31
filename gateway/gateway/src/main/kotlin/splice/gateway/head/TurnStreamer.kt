// NEW: the SSE writer open + per-turn channel/emitter assembly.
// Split from TurnDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.util.Cancellables
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.SseEmitterFactory
import splice.gateway.wire.TurnWiring
import splice.spi.Provider
import java.util.concurrent.atomic.AtomicBoolean

internal class TurnStreamer(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val driveFactory: TurnDriveFactory,
    private val driver: TurnDriver,
) {
    private val emitters = SseEmitterFactory()
    private val wiring = TurnWiring()

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, inputs: TurnInputs) {
        val built = inputs.built
        val perf = inputs.perf
        call.respondTextWriter(ContentType.Text.EventStream) {
            // Flush-per-frame: a frame buffered across an upstream lull is invisible to the
            // user exactly when responsiveness matters (see ImmediateSseWriter header).
            val channel = ClientChannel(
                coalesced = ImmediateSseWriter(writeRaw = { frame -> write(frame) }, flushRaw = { flush() }),
                writeMutex = Mutex(),
                clientGone = AtomicBoolean(false),
            )
            val emitter = emitters.create(
                write = { frame ->
                    channel.writeMutex.withLock { channel.timedClientWrite(frame, perf, deps.clock) }
                },
                model = built.meta.originalModel,
                usagePayload = wiring.usagePayloadBuilder(provider.catalog, built.meta),
            )
            val drive = driveFactory.assembleDrive(inputs, emitter, channel)
            try {
                // The 200 + SSE headers are committed once respondTextWriter opens, so any failure
                // must become an honest `event: error` frame — NOT escape and leave the client an
                // empty/truncated 200 (the "empty or malformed response (HTTP 200)" class).
                driver.driveSealingCancellation(drive)
            } finally {
                // Terminal frames force-flush already; this covers abandon / exception paths.
                // DR-93: on a dead socket the flush ITSELF throws — inside a finally, that
                // replaces the in-flight CancellationException (or the turn's real failure) with
                // the flush failure, hiding the causal fault from the Ktor pipeline. The flush is
                // redundant idempotent cleanup: its own failure is discarded, cancellation
                // propagates (runCatchingCancellable rethrows it by contract).
                Cancellables.runCatchingCancellable { channel.coalesced.flush() }
            }
        }
    }
}

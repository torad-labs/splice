package splice.head.wire.observation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import splice.core.perf.TurnPerf
import splice.core.perf.observation.PerfRequestRecorder
import splice.core.util.ElapsedClock
import splice.core.util.JsonWire
import splice.head.turn.delivery.CollectedReply
import splice.head.wire.CollectingTerminal
import splice.head.wire.FrameWrite
import splice.head.wire.OK_STATUS
import java.io.Writer

/** Collected local replies share the same observed write-and-flush boundary as driven replies. */
internal object LocalResponseDelivery {
    fun terminal(
        terminal: CollectingTerminal,
        perf: TurnPerf? = null,
        observations: PerfRequestRecorder? = perf?.observations,
    ): CollectedReply = CollectedReply(
        JsonWire.string(terminal.responseBody()),
        HttpStatusCode.fromValue(terminal.httpStatus()),
        perf,
        observations,
    )

    /** [status]: a held reply carries its own, a failure's included (HeldReply). */
    fun body(body: JsonObject, perf: TurnPerf, status: Int = OK_STATUS): CollectedReply =
        CollectedReply(JsonWire.string(body), HttpStatusCode.fromValue(status), perf)
}

/** Local and replayed frames become observations only after their client write and flush succeed. */
internal class LocalFrameDelivery(
    private val out: Writer,
    private val observation: PerfRequestRecorder?,
    private val clock: ElapsedClock,
) : FrameWrite {
    override suspend fun invoke(frame: String) {
        val at = clock()
        out.write(frame)
        out.flush()
        DeliveryObservation.frame(observation, frame, clock() - at)
    }
}

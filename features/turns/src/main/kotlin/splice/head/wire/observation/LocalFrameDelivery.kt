package splice.head.wire.observation

import splice.core.perf.observation.PerfRequestRecorder
import splice.core.util.ElapsedClock
import splice.head.wire.FrameWrite
import java.io.Writer

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

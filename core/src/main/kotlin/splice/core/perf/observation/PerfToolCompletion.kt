package splice.core.perf.observation

import java.util.concurrent.atomic.AtomicBoolean

/** A single prepared tool terminal, retaining its measured time until external finalization has completed. */
public class PerfToolCompletion internal constructor(
    private val recorder: PerfRecorder,
    private val observation: PerfObservation,
) {
    private val published = AtomicBoolean()

    /** Publishes once, including when the external finalizer failed; observer refusal remains a false result. */
    public fun publish(): Boolean = published.compareAndSet(false, true) && recorder.publish(observation)
}

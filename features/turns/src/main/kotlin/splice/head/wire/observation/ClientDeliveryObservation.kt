package splice.head.wire.observation

import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRequestRecorder

/** Channel-local progress includes comment keepalives without changing the legacy model-frame clocks. */
internal class ClientDeliveryObservation {
    @Volatile private var request: PerfRequestRecorder? = null
    private var previousMs: Long? = null

    fun frame(request: PerfRequestRecorder?, frame: String, writeMs: Long, model: Boolean) {
        if (request == null) return
        this.request = request
        val now = request.elapsedMs()
        previousMs?.let { request.observe(PerfEventKind.GAP, mapOf("downstream_gap_ms" to (now - it).toDouble())) }
        previousMs = now
        DeliveryObservation.frame(request, frame, writeMs, model)
    }

    fun elapsedMs(): Long? = request?.elapsedMs()

    fun keepalive(frame: String, started: Long?) {
        val elapsed = started?.let { request?.elapsedMs()?.minus(it) } ?: return
        this.frame(request, frame, elapsed, model = false)
    }

    fun abandoned() {
        request?.finish(OutcomeTag.CLIENT_ABORT.wire)
    }
}

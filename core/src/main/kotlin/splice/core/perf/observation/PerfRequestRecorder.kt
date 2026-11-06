package splice.core.perf.observation

/** A request's single terminal owner. Refining the scope moves live gauges without moving its arrival population. */
public class PerfRequestRecorder internal constructor(
    private val recorder: PerfRecorder,
    initialScope: PerfScope,
    initialLinks: PerfLinks,
    private val arrivalMonotonicMs: Long,
) {
    private val lock = Any()
    private var currentScope = initialScope
    private var currentLinks = initialLinks
    private var finished = false
    private var admitted = false
    private val measurements = PerfRequestMeasurements()
    private val canonicalUsage = PerfCanonicalUsage(recorder)

    public val links: PerfLinks get() = synchronized(lock) { currentLinks }
    public val scope: PerfScope get() = synchronized(lock) { currentScope }

    public fun attempt(transport: String): PerfAttemptRecorder = synchronized(lock) {
        recorder.attempt(currentScope, currentLinks, transport)
    }

    public fun usage(values: Map<String, Double>): Boolean = synchronized(lock) { canonicalUsage.record(values) }

    public fun update(scope: PerfScope, links: PerfLinks = this.links): Unit = synchronized(lock) {
        if (!finished) {
            currentScope = PerfDimensions.scope(scope)
            currentLinks = PerfDimensions.links(links).copy(requestId = currentLinks.requestId)
            recorder.emit(currentScope, PerfEventKind.CHECKPOINT, links = currentLinks)
        }
    }

    public fun observe(
        kind: PerfEventKind,
        values: Map<String, Double> = emptyMap(),
        outcome: String? = null,
    ): Boolean = synchronized(lock) {
        when {
            kind == PerfEventKind.USAGE_RECORDED -> usage(values)
            finished || kind == PerfEventKind.REQUEST_ARRIVED -> false
            kind == PerfEventKind.REQUEST_FINISHED -> finish(outcome ?: "?", values)
            kind == PerfEventKind.REQUEST_ADMITTED && admitted -> false
            else -> observeOpen(kind, values, outcome)
        }
    }

    public fun finish(outcome: String, values: Map<String, Double> = emptyMap()): Boolean = synchronized(lock) {
        if (finished) return false
        finished = true
        val terminal = PerfObservation(
            recorder.stamp(),
            currentScope,
            PerfEventKind.REQUEST_FINISHED,
            measurements.terminal(values, elapsedMs()),
            currentLinks,
            PerfDimensions.terminalOutcome(outcome),
        )
        val recorded = recorder.publish(terminal)
        val usageRecorded = canonicalUsage.finish(terminal, values)
        recorded && usageRecorded
    }

    public fun elapsedMs(): Long = recorder.elapsedSince(arrivalMonotonicMs)

    private fun observeOpen(kind: PerfEventKind, values: Map<String, Double>, outcome: String?): Boolean {
        if (kind == PerfEventKind.REQUEST_ADMITTED) admitted = true
        measurements.observe(kind, values)
        return recorder.emit(currentScope, kind, values, currentLinks, outcome)
    }
}

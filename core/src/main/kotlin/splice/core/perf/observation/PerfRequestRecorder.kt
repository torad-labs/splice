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
    private val terminalMeasurements = LinkedHashMap<String, Double>()

    public val links: PerfLinks get() = synchronized(lock) { currentLinks }
    public val scope: PerfScope get() = synchronized(lock) { currentScope }

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
            finished || kind == PerfEventKind.REQUEST_ARRIVED -> false
            kind == PerfEventKind.REQUEST_FINISHED -> finish(outcome ?: "?", values)
            kind == PerfEventKind.REQUEST_ADMITTED && admitted -> false
            else -> observeOpen(kind, values, outcome)
        }
    }

    public fun finish(outcome: String, values: Map<String, Double> = emptyMap()): Boolean = synchronized(lock) {
        if (finished) return false
        finished = true
        val terminalValues = values.filterKeys { !PerfMetricRegistry.isContentTiming(it) }
        recorder.emit(
            currentScope,
            PerfEventKind.REQUEST_FINISHED,
            terminalValues + terminalMeasurements + ("arrival_to_terminal_ms" to elapsedMs().toDouble()),
            currentLinks,
            outcome,
        )
    }

    public fun elapsedMs(): Long = recorder.elapsedSince(arrivalMonotonicMs)

    private fun observeOpen(kind: PerfEventKind, values: Map<String, Double>, outcome: String?): Boolean {
        if (kind == PerfEventKind.REQUEST_ADMITTED) {
            admitted = true
            values["queue_wait_ms"]?.takeIf { PerfMetricRegistry.accepts("queue_wait_ms", kind, it) }
                ?.let { terminalMeasurements.putIfAbsent("queue_wait_ms", it) }
        }
        if (kind == PerfEventKind.CONTENT_PROGRESS) {
            values.forEach { (key, value) ->
                if (PerfMetricRegistry.isContentTiming(key) && PerfMetricRegistry.accepts(key, kind, value)) {
                    terminalMeasurements.putIfAbsent(key, value)
                }
            }
        }
        if (kind == PerfEventKind.GAP) captureGapMaxima(values)
        return recorder.emit(currentScope, kind, values, currentLinks, outcome)
    }

    private fun captureGapMaxima(values: Map<String, Double>) {
        listOf("upstream_gap_ms" to "upstream_gap_max_ms", "downstream_gap_ms" to "downstream_gap_max_ms")
            .forEach { (event, maximum) ->
                values[event]?.takeIf { PerfMetricRegistry.accepts(event, PerfEventKind.GAP, it) }?.let { value ->
                    terminalMeasurements[maximum] = maxOf(terminalMeasurements[maximum] ?: 0.0, value)
                }
            }
    }
}

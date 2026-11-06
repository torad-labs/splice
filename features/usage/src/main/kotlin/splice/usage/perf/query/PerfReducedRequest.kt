package splice.usage.perf.query

import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp

/** Scalar request state. Observation ids and progress payloads are unnecessary after source deduplication. */
internal class PerfReducedRequest(
    private val id: String,
    val identity: PerfIdentity,
    private val memory: PerfReductionMemory,
) {
    var hasRequestedEvidence = false
    private var arrivalWall = -1L
    private var arrivalMono = 0L
    private var arrivalOutcome: String? = null
    private var terminalWall = -1L
    private var terminalMono = 0L
    private var usageWall = -1L
    private var usageMono = 0L
    private var usageOutcome: String? = null
    private var checkpointWall = 0L
    private var checkpointMono = 0L
    private var checkpointOutcome: String? = null
    private var arrivalScope: PerfScope? = null
    private var usageScope: PerfScope? = null
    private var scope: PerfScope? = null
    private var outcome: String? = null
    private var checkpoint = false
    private var usageMultiple = false
    private var links = PerfReducedValues.emptyLinks
    private val terminal = DoubleArray(PerfReducedValues.terminal.size) { Double.NaN }
    private val usage = DoubleArray(PerfReducedValues.usage.size) { Double.NaN }
    private var firstAttempt: ReducedAttempt? = null
    private var moreAttempts: MutableList<ReducedAttempt>? = null

    fun accept(event: PerfObservation, selectedScope: PerfScope, selectedLinks: PerfLinks): Boolean {
        if (scope == null) scope = selectedScope
        return when (event.kind) {
            PerfEventKind.REQUEST_ARRIVED -> {
                arrival(event, selectedScope, selectedLinks)
                true
            }
            PerfEventKind.REQUEST_FINISHED -> { terminal(event, selectedScope, selectedLinks); true }
            PerfEventKind.USAGE_RECORDED -> { usage(event, selectedScope); true }
            PerfEventKind.ATTEMPT_STARTED, PerfEventKind.ATTEMPT_FINISHED -> attempt(event, selectedScope)
            else -> {
                checkpoint(event, selectedScope, selectedLinks)
                true
            }
        }
    }

    private fun arrival(event: PerfObservation, selectedScope: PerfScope, selectedLinks: PerfLinks) {
        if (arrivalWall >= 0) return
        arrivalWall = event.stamp.wallMs
        arrivalMono = event.stamp.monotonicMs
        arrivalOutcome = event.outcome
        arrivalScope = selectedScope
        if (!checkpoint && terminalWall < 0) links = selectedLinks
    }

    private fun checkpoint(event: PerfObservation, selectedScope: PerfScope, selectedLinks: PerfLinks) {
        if (event.kind == PerfEventKind.CHECKPOINT && terminalWall < 0) {
            scope = selectedScope; links = selectedLinks; checkpoint = true
            checkpointWall = event.stamp.wallMs; checkpointMono = event.stamp.monotonicMs
            checkpointOutcome = event.outcome
        }
    }

    private fun terminal(event: PerfObservation, selectedScope: PerfScope, selectedLinks: PerfLinks) {
        if (terminalWall >= 0) return
        terminalWall = event.stamp.wallMs; terminalMono = event.stamp.monotonicMs
        scope = selectedScope; links = selectedLinks; outcome = event.outcome
        PerfReducedValues.copy(event, PerfReducedValues.terminal, terminal)
        if (usageWall < 0) PerfReducedValues.copy(event, PerfReducedValues.usage, usage)
    }

    private fun usage(event: PerfObservation, selectedScope: PerfScope) {
        if (usageWall >= 0) { usageMultiple = true; return }
        usageWall = event.stamp.wallMs; usageMono = event.stamp.monotonicMs
        usageScope = selectedScope; usageOutcome = event.outcome
        PerfReducedValues.copy(event, PerfReducedValues.usage, usage)
    }

    private fun attempt(event: PerfObservation, scope: PerfScope): Boolean {
        val attemptId = event.links.attemptId
        val existing = firstAttempt?.takeIf { it.id == attemptId } ?: moreAttempts?.firstOrNull { it.id == attemptId }
        val record = existing ?: newAttempt(attemptId, scope) ?: return false
        record.accept(event, scope)
        return true
    }

    private fun newAttempt(attemptId: String?, scope: PerfScope): ReducedAttempt? {
        if (!memory.take(ATTEMPT_FIXED_BYTES + (attemptId?.length ?: 0) * 2L)) return null
        return ReducedAttempt(attemptId, scope).also {
            if (firstAttempt == null) {
                firstAttempt = it
            } else {
                val more = moreAttempts ?: ArrayList<ReducedAttempt>().also { values -> moreAttempts = values }
                more.add(it)
            }
        }
    }

    fun events(): List<PerfObservation> = boundaries().toMutableList().apply {
        firstAttempt?.let { addAll(it.events(identity, id)) }
        moreAttempts?.forEach { addAll(it.events(identity, id)) }
    }

    private fun boundaries(): List<PerfObservation> = buildList {
        if (arrivalWall >= 0) {
            add(
                event(PerfEventKind.REQUEST_ARRIVED, arrivalWall, arrivalMono, requireNotNull(arrivalScope)),
            )
        }
        val current = scope ?: arrivalScope ?: PerfScope()
        if (terminalWall >= 0) {
            add(event(PerfEventKind.REQUEST_FINISHED, terminalWall, terminalMono, current, terminalValues()))
        } else if (checkpoint) {
            add(event(PerfEventKind.CHECKPOINT, checkpointWall, checkpointMono, current))
        }
        if (usageWall >= 0) {
            add(
                event(
                    PerfEventKind.USAGE_RECORDED,
                    usageWall,
                    usageMono,
                    usageScope ?: current,
                    PerfReducedValues.values(PerfReducedValues.usage, usage),
                ),
            )
        }
        if (usageMultiple) add(event(PerfEventKind.USAGE_RECORDED, usageWall, usageMono, current))
    }

    private fun event(
        kind: PerfEventKind,
        wall: Long,
        mono: Long,
        scope: PerfScope,
        values: Map<String, Double> = emptyMap(),
    ): PerfObservation =
        PerfObservation(
            PerfStamp("reduced", identity, wall, mono),
            scope,
            kind,
            values,
            links.copy(requestId = id),
            when (kind) {
                PerfEventKind.REQUEST_ARRIVED -> arrivalOutcome
                PerfEventKind.CHECKPOINT -> checkpointOutcome
                PerfEventKind.USAGE_RECORDED -> usageOutcome
                else -> outcome
            },
        )

    private fun terminalValues(): Map<String, Double> = PerfReducedValues.values(PerfReducedValues.terminal, terminal) +
        if (usageWall < 0) PerfReducedValues.values(PerfReducedValues.usage, usage) else emptyMap()
}

private class ReducedAttempt(val id: String?, scope: PerfScope) {
    private var started = -1L
    private var startedMono = 0L
    private var finished = -1L
    private var finishedMono = 0L
    private var duration = Double.NaN
    private var finalScope = scope
    private var initialScope = scope
    private var outcome: String? = null

    fun accept(event: PerfObservation, selectedScope: PerfScope) {
        if (event.kind == PerfEventKind.ATTEMPT_STARTED && started < 0) {
            started = event.stamp.wallMs; startedMono = event.stamp.monotonicMs
            initialScope = selectedScope
        }
        if (event.kind == PerfEventKind.ATTEMPT_FINISHED && finished < 0) {
            finished = event.stamp.wallMs; finishedMono = event.stamp.monotonicMs
            duration = event.values["attempt_duration_ms"] ?: Double.NaN
            finalScope = selectedScope; outcome = event.outcome
        }
    }

    fun events(identity: PerfIdentity, request: String): List<PerfObservation> = buildList {
        val links = PerfLinks(requestId = request, attemptId = id)
        if (started >= 0) {
            add(
                PerfObservation(
                    PerfStamp("reduced", identity, started, startedMono),
                    initialScope,
                    PerfEventKind.ATTEMPT_STARTED,
                    links = links,
                ),
            )
        }
        if (finished >= 0) {
            add(
                PerfObservation(
                    PerfStamp("reduced", identity, finished, finishedMono),
                    finalScope,
                    PerfEventKind.ATTEMPT_FINISHED,
                    if (duration.isFinite()) mapOf("attempt_duration_ms" to duration) else emptyMap(),
                    links,
                    outcome,
                ),
            )
        }
    }
}

internal object PerfReducedValues {
    val emptyLinks = PerfLinks()
    val terminal = listOf(
        "arrival_to_terminal_ms", "arrival_to_first_meaningful_content_ms", "arrival_to_first_reasoning_ms",
        "arrival_to_first_text_ms", "arrival_to_first_tool_ms", "arrival_to_first_client_byte_ms",
        "queue_wait_ms", "upstream_gap_max_ms", "downstream_gap_max_ms",
    )
    val usage = listOf(
        "input_tokens", "output_tokens", "cached_tokens", "missing_usage", "usage_cache_write_tokens",
        "usage_cache_write_1h_tokens", "usage_absorbed_rounds", "usage_cut_rounds", "usage_no_request",
        "usage_observed_input", "usage_observed_output", "usage_observed_cache_read",
        "usage_observed_cache_write", "usage_observed_cache_write_1h",
    )

    fun copy(event: PerfObservation, keys: List<String>, target: DoubleArray) {
        keys.forEachIndexed { index, key -> target[index] = event.values[key] ?: Double.NaN }
    }

    fun values(keys: List<String>, values: DoubleArray): Map<String, Double> = buildMap {
        keys.forEachIndexed { index, key -> if (values[index].isFinite()) put(key, values[index]) }
    }
}

private const val ATTEMPT_FIXED_BYTES = 144L

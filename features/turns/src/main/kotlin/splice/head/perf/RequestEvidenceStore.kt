package splice.head.perf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfProjection
import splice.core.util.FileIoSink
import splice.core.util.FileIoWrite
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock

/** Local numeric request evidence. Enqueue is bounded; JSON encoding and persistence stay on the file lane. */
public class RequestEvidenceStore internal constructor(
    private val files: RequestEvidenceFiles,
    private val head: String,
    clock: WallClock,
) : PerfObserver {
    public val sink: FileIoSink = FileIoSink("request_evidence:$head", clock)
    private val accepted = setOf(
        PerfEventKind.REQUEST_ARRIVED,
        PerfEventKind.REQUEST_FINISHED,
        PerfEventKind.CHECKPOINT,
        PerfEventKind.USAGE_RECORDED,
        PerfEventKind.TOOL_STARTED,
        PerfEventKind.TOOL_PHASE,
        PerfEventKind.TOOL_FINISHED,
        PerfEventKind.ATTEMPT_STARTED,
        PerfEventKind.ATTEMPT_FINISHED,
    )

    override fun record(observation: PerfObservation): Boolean {
        if (observation.kind !in accepted || observation.links.requestId == null) return true
        return files.admit { if (valid(observation)) enqueue(observation) else refused() }
    }

    private fun refused(): Boolean {
        sink.reject()
        files.faultCoverage(head)
        return false
    }

    private fun valid(observation: PerfObservation): Boolean = observation.scope.head == head &&
        observation.values.all { (key, value) ->
            numeric(
                value,
            ) && PerfMetricRegistry.permits(key, PerfProjection.LOCAL)
        }

    private fun numeric(value: Double): Boolean = value.isFinite() && value >= 0

    private fun enqueue(observation: PerfObservation): Boolean {
        val budget = PerfWorkBudget.acquire(estimate(observation)) ?: return refused()
        val retained = observation.copy(values = observation.values.toMap())
        val queued = files.submit(
            head,
            sink,
            FileIoWrite {
                try {
                    files.append(head, Json.encodeToString(retained))
                    true
                } finally {
                    budget.close()
                }
            },
        )
        if (!queued) budget.close()
        return queued
    }

    /** Recorder labels/links are bounded; identity strings are included separately rather than assumed small. */
    private fun estimate(observation: PerfObservation): Long {
        val identity = observation.stamp.identity
        val identityChars = identity.bootId.length.toLong() + identity.build.length + identity.version.length +
            identity.configRevision.length
        return EVIDENCE_METADATA_BYTES + identityChars * EVIDENCE_IDENTITY_CHAR_BYTES +
            observation.values.size * EVIDENCE_VALUE_BYTES
    }
}

// Charge 8 KiB for observation metadata and bounded links before producer enqueue.
private const val EVIDENCE_METADATA_BYTES = 8_192L

// Charge 256 bytes per metric value for its key, boxed number and map entry.
private const val EVIDENCE_VALUE_BYTES = 256L

// Charge two bytes per retained identity character even when the JVM uses compact strings.
private const val EVIDENCE_IDENTITY_CHAR_BYTES = 2L

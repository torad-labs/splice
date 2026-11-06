package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.query.PerfQueryJson.textValue

/** Representative evidence only; its independent truncation never becomes a request population limit. */
internal class PerfRepresentativeTimeline(private val memory: PerfReductionMemory) {
    private val groups = LinkedHashMap<ReducedRequestKey, MutableList<PerfObservation>>()
    private val selected = HashSet<ReducedRequestKey>()
    private var bytes = 0L
    private var truncated = false

    fun contains(key: ReducedRequestKey): Boolean = key in groups

    fun capture(key: ReducedRequestKey, event: PerfObservation) {
        if (event.kind !in TIMELINE_KINDS) { truncated = true; return }
        val group = group(key) ?: return
        if (!makeRoom(group, event)) return
        val charge = charge(event)
        if (bytes + charge <= MAX_BYTES && memory.take(charge)) {
            group.add(
                event.copy(
                    values = event.values.toMap(),
                    scope = event.scope.copy(),
                    links = event.links.copy(),
                    stamp = event.stamp.copy(identity = event.stamp.identity.copy()),
                ),
            )
            bytes += charge
        } else {
            truncated = true
        }
    }

    private fun group(key: ReducedRequestKey): MutableList<PerfObservation>? =
        groups[key] ?: if (groups.size < MAX_REQUESTS) {
            ArrayList<PerfObservation>().also { groups[key] = it }
        } else { truncated = true; null }

    private fun makeRoom(group: MutableList<PerfObservation>, event: PerfObservation): Boolean {
        if (group.size < MAX_EVENTS) return true
        truncated = true
        if (event.kind !in setOf(PerfEventKind.REQUEST_FINISHED, PerfEventKind.USAGE_RECORDED)) return false
        val removed = charge(group.removeLast())
        bytes -= removed
        memory.give(removed)
        return true
    }

    fun select(key: ReducedRequestKey) { if (key in groups) selected.add(key) }

    fun render(matching: Long): JsonObject = buildJsonObject {
        val kept = groups.filterKeys { it in selected }.values.filter { it.isNotEmpty() }
        put("truncated", truncated || matching > kept.size)
        put(
            "requests",
            JsonArray(
                kept.map { group ->
                    val linked = group.lastOrNull { it.links.turnId != null } ?: group.last()
                    buildJsonObject {
                        put("request_id", textValue(linked.links.requestId))
                        put("head", linked.scope.head)
                        put("turn_id", textValue(linked.links.turnId))
                        put("trace_id", textValue(linked.links.traceId))
                        put("session_id", textValue(linked.links.sessionId))
                        put(
                            "events",
                            JsonArray(group.sortedBy { it.stamp.monotonicMs }.map(PerfEvidenceJson::timelineEvent)),
                        )
                    }
                },
            ),
        )
    }

    private fun charge(event: PerfObservation): Long =
        EVENT_BYTES + event.values.size * VALUE_BYTES + listOfNotNull(
            event.stamp.observationId,
            event.stamp.identity.bootId,
            event.stamp.identity.build,
            event.stamp.identity.version,
            event.stamp.identity.configRevision,
            event.links.requestId,
            event.links.attemptId,
            event.links.sessionId,
            event.links.traceId,
            event.links.turnId,
            event.links.accountId,
            event.links.projectId,
            event.scope.head,
            event.scope.model,
            event.scope.transport,
            event.scope.work,
            event.scope.outcome,
            event.outcome,
            event.links.toolId,
        ).sumOf { it.length * CHAR_BYTES } + event.values.keys.sumOf { it.length * CHAR_BYTES }
}

internal data class ReducedRequestKey(val boot: String, val request: String)
private const val MAX_REQUESTS = 20
private const val MAX_EVENTS = 8
private const val MAX_BYTES = 128L * 1_024
private const val EVENT_BYTES = 768L
private const val VALUE_BYTES = 96L
private const val CHAR_BYTES = 4L
private val TIMELINE_KINDS = setOf(
    PerfEventKind.REQUEST_ARRIVED,
    PerfEventKind.CHECKPOINT,
    PerfEventKind.REQUEST_FINISHED,
    PerfEventKind.USAGE_RECORDED,
    PerfEventKind.ATTEMPT_STARTED,
    PerfEventKind.ATTEMPT_FINISHED,
    PerfEventKind.TOOL_STARTED,
    PerfEventKind.TOOL_FINISHED,
)

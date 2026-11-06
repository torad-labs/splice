package splice.usage.perf.objectives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceRead
import splice.usage.perf.history.PerfEvidenceSource
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Path

internal class ObjectiveFixture(directory: Path) : AutoCloseable {
    val store = PerfObjectiveStore(directory.resolve("objectives.json"))
    var now = 120_000L
    var duration = 200.0
    var count = 2
    var covered = true
    var outcome = OutcomeTag.OK.wire
    var settingsDuringRead: String? = null
    val delivery = RecordingDelivery()
    val query = PerfQueryService(
        object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun evidence(): PerfEvidenceSource = PerfEvidenceSource { selected ->
                settingsDuringRead?.let { replacement ->
                    settingsDuringRead = null
                    configure(replacement)
                }
                val events = (0 until count).flatMap { index ->
                    listOf(
                        event("arrive-$index", index, selected.untilMs - 500, PerfEventKind.REQUEST_ARRIVED),
                        event("finish-$index", index, selected.untilMs - 100, PerfEventKind.REQUEST_FINISHED),
                    )
                }
                PerfEvidenceRead(
                    events,
                    PerfCoverage(
                        selected.fromMs,
                        selected.untilMs,
                        if (covered) selected.untilMs - selected.fromMs else 0,
                    ),
                    now,
                    true,
                )
            }
        },
        clock = WallClock { now },
    )
    var engine = PerfAlertEngine(query, store, delivery)

    fun configure(raw: String = objectiveSettings()): JsonObject = takeReply(
        store.update(Json.parseToJsonElement(raw).jsonObject),
    )

    fun evaluate(at: Long): JsonObject {
        now = at
        return takeReply(engine.evaluate(at))
    }

    fun restart() {
        engine.close()
        engine = PerfAlertEngine(query, store, delivery)
    }

    override fun close() {
        engine.close()
        store.close()
    }

    private fun event(id: String, request: Int, at: Long, kind: PerfEventKind): PerfObservation = PerfObservation(
        PerfStamp("$id-$now", PerfIdentity("boot", "build", "version", "config"), at, at),
        PerfScope(head = "h", model = "m", transport = "http"),
        kind,
        if (kind == PerfEventKind.REQUEST_FINISHED) mapOf("arrival_to_terminal_ms" to duration) else emptyMap(),
        PerfLinks(requestId = request.toString()),
        outcome.takeIf { kind == PerfEventKind.REQUEST_FINISHED },
    )
}

internal class RecordingDelivery : PerfAlertDelivery {
    val events = mutableListOf<JsonObject>()
    var accepts = true
    var completion: String? = null
    override fun enqueue(event: JsonObject): Boolean {
        events.add(event)
        return accepts
    }
    override fun result(dedupKey: String): String? = completion
}

internal fun objectiveSettings(
    metric: String = "histograms.arrival_to_terminal_ms.max",
    delivery: Boolean = false,
    hold: Long = 10_000,
    threshold: Double = 100.0,
    recovery: Double = 50.0,
): String = """{
    "schema_version":1,
    "objectives":[{"id":"latency","cohort":{"head":"h"},"completion_ms":100}],
    "rules":[{"id":"slow","objective_id":"latency","cohort":{},"metric":"$metric",
      "threshold":$threshold,"window_ms":60000,"min_count":2,"min_coverage":0.9,
      "hold_ms":$hold,"cooldown_ms":30000,"recovery":{"threshold":$recovery,"hold_ms":10000},
      "comparison":"${if (metric.startsWith("goodput.")) "below" else "above"}","delivery_enabled":$delivery}]
}"""

internal fun takeReply(reply: JsonObject): JsonObject = try {
    Json.parseToJsonElement(reply.toString()).jsonObject
} finally {
    PerfWorkBudget.release(reply)
}

internal fun JsonObject.event(): JsonObject = (getValue("events") as JsonArray).first().jsonObject

internal fun JsonObject.obj(key: String): JsonObject = ObjectiveJson.obj(this, key)
internal fun JsonObject.text(key: String): String? = ObjectiveJson.text(this, key)
internal fun JsonObject.number(key: String): Double? = ObjectiveJson.number(this, key)
internal fun JsonObject.long(key: String): Long? = ObjectiveJson.long(this, key)

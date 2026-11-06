package splice.usage.perf.objectives

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import splice.core.util.PerfWorkBudget
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService

internal data class AlertEvaluationTime(val now: Long, val restarted: Boolean)

internal class AlertRuleEvaluation(private val query: PerfQueryService, private val outbox: AlertOutbox) {
    fun evaluate(
        data: AlertJournalData,
        settings: ObjectiveSettings,
        rule: AlertRule,
        time: AlertEvaluationTime,
    ): AlertJournalData {
        val objective = settings.objectives.find { it.id == rule.objectiveId }
        val context =
            AlertContext(objective?.cohort.orEmpty() + rule.cohort, objective, time.restarted, settings.revision)
        val revision = ObjectiveJson.hash(ObjectiveJson.codec.encodeToString(rule) + objective?.revision.orEmpty())
        val current = AlertRetirement.changed(data, rule.id, revision, time.now)
        val previous = current.states[rule.id] ?: AlertState(rule.id, revision)
        val measured = measure(rule, context, time.now)
        val state = AlertLifecycle.advance(previous, rule, measured, time.now, time.restarted)
        val event = deliveryState(AlertEvents.render(state, rule, context, measured, time.now), current)
        val changed = state.copy(
            lastEvaluation = null,
        ) != previous.copy(lastEvaluation = null) || rule.id !in current.latest
        val events = if (changed) {
            current.events.filterNot {
                ObjectiveJson.text(
                    it,
                    DEDUP_KEY,
                ) == ObjectiveJson.text(event, DEDUP_KEY)
            } + event
        } else {
            current.events
        }
        val next = current.copy(
            states = current.states + (rule.id to state),
            latest = current.latest + (rule.id to event),
            events = events,
        )
        return if (state.state == "firing" && !previous.active) outbox.add(next, event, rule, revision) else next
    }

    private fun deliveryState(event: JsonObject, data: AlertJournalData): JsonObject {
        val key = ObjectiveJson.text(event, DEDUP_KEY)
        val attempt = data.deliveries.find { it.key == key }
        val previous = data.latest[
            ObjectiveJson.text(
                event,
                "rule_id",
            ),
        ]?.takeIf { ObjectiveJson.text(it, DEDUP_KEY) == key }
        val status = attempt?.status ?: previous?.let { ObjectiveJson.text(it, "delivery_status") } ?: return event
        return JsonObject(event + ("delivery_status" to ObjectiveJson.text(status)))
    }

    private fun measure(rule: AlertRule, context: AlertContext, now: Long): AlertMeasurement {
        if (now < rule.windowMs) {
            return AlertMeasurements.measured(null, 0, JsonObject(emptyMap()), rule.windowMs, "window_before_epoch")
        }
        val result = query.summary(PerfQuery(now - rule.windowMs, now, context.cohort, context.objective?.objective()))
        return try { AlertMeasurements.read(rule, result, now) } finally { PerfWorkBudget.release(result) }
    }
}

private const val DEDUP_KEY = "dedup_key"

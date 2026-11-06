package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.util.PerfWorkBudget
import splice.usage.perf.query.PerfQueryService
import java.util.concurrent.locks.ReentrantLock

/** Blocking local evaluation only; the host schedules it on a bounded I/O lane, never inference or Netty. */
public class PerfAlertEngine(
    private val query: PerfQueryService,
    private val store: PerfObjectiveStore,
    private val delivery: PerfAlertDelivery,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val lease = PerfWorkBudget.acquire(ENGINE_BYTES)
    private val journal = AlertJournal(store.lifecyclePath(), lease != null)
    private val outbox = AlertOutbox(delivery, journal, store)
    private val evaluator = AlertRuleEvaluation(query, outbox)
    private var restarted = journal.restarted
    private var closed = false

    public fun evaluate(nowMs: Long): JsonObject {
        if (!lock.tryLock()) return ObjectiveJson.failure("evaluation_in_progress", "busy")
        return try {
            evaluateLocked(nowMs)
        } finally {
            lock.unlock()
        }
    }

    public fun history(fromMs: Long, untilMs: Long): JsonObject {
        if (fromMs < 0 || untilMs <= fromMs) return ObjectiveJson.failure("invalid_interval")
        if (!lock.tryLock()) return ObjectiveJson.failure("evaluation_in_progress", "busy")
        return try {
            ObjectiveJson.reply(
                buildJsonObject {
                    put("schema_version", 1)
                    put("status", if (journal.issue == null) OutcomeTag.OK.wire else "partial")
                    put(
                        "events",
                        JsonArray(
                            journal.data.events.filter {
                                (
                                    ObjectiveJson.long(
                                        it,
                                        "as_of_ms",
                                    ) ?: 0
                                    ) in fromMs until untilMs
                            },
                        ),
                    )
                    put("expired_events", journal.data.expiredEvents)
                    put("retention", "bounded_transition_history")
                },
            )
        } finally {
            lock.unlock()
        }
    }

    public fun status(): JsonObject {
        if (!lock.tryLock()) return ObjectiveJson.failure("evaluation_in_progress", "busy")
        return try { report() } finally { lock.unlock() }
    }

    override fun close() {
        lock.lock()
        try {
            if (!closed) {
                closed = true
                journal.clear()
                lease?.close()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun evaluateLocked(now: Long): JsonObject {
        val problem = startProblem(now)
        if (problem != null) return ObjectiveJson.failure(problem)
        val settings = store.snapshot() ?: return ObjectiveJson.failure("settings_unavailable")
        return when {
            journal.loadFailed -> report()
            journal.data.lastEvaluation?.let { now <= it } == true -> report("late_window_ignored")
            settings.rules.isEmpty() && journal.data.states.isEmpty() -> {
                outbox.advance(now)
                report()
            }
            else -> evaluateSettings(settings, now)
        }
    }

    private fun startProblem(now: Long): String? = when {
        closed || lease == null -> "engine_unavailable"
        now < 0 -> "invalid_time"
        else -> null
    }

    private fun evaluateSettings(settings: ObjectiveSettings, now: Long): JsonObject {
        var next = AlertRetirement.removeMissing(journal.data, settings, now)
        for (rule in settings.rules) next = evaluator.evaluate(
            next,
            settings,
            rule,
            AlertEvaluationTime(now, restarted),
        )
        val publication = store.publishIfCurrent(settings.revision) { journal.publish(next.copy(lastEvaluation = now)) }
        when (publication) {
            ObjectivePublicationResult.SETTINGS_CHANGED -> return report("settings_changed_during_evaluation")
            ObjectivePublicationResult.PUBLISHED -> {
                restarted = false
                outbox.advance(now)
            }
            ObjectivePublicationResult.FAILED -> Unit
        }
        return report()
    }

    private fun report(reason: String? = null): JsonObject {
        val transport = delivery.status()
        return try {
            ObjectiveJson.reply(
                buildJsonObject {
                    put("schema_version", 1)
                    put(
                        "status",
                        if (listOf(
                                journal.issue == null,
                                !closed,
                                lease != null,
                            ).all { it }
                        ) {
                            OutcomeTag.OK.wire
                        } else {
                            "partial"
                        },
                    )
                    put("as_of_ms", ObjectiveJson.number(journal.data.lastEvaluation))
                    put("evaluated_at_ms", ObjectiveJson.number(journal.data.lastEvaluation))
                    put("events", JsonArray(journal.data.latest.values.toList()))
                    put("reason", ObjectiveJson.text(reason ?: journal.issue))
                    put("restart_uncertain", restarted)
                    put("expired_events", journal.data.expiredEvents)
                    put("delivery_rejected", journal.data.deliveryRejected)
                    put("delivery_failures", journal.data.deliveryFailures)
                    put("enqueue_failures", journal.data.enqueueFailures)
                    put("delivery", transport)
                    put(
                        "attempts",
                        JsonArray(
                            journal.data.deliveries.map {
                                buildJsonObject {
                                    put("dedup_key", it.key); put("attempts", it.attempts); put("status", it.status)
                                }
                            },
                        ),
                    )
                },
            )
        } finally {
            PerfWorkBudget.release(transport)
        }
    }
}

private const val ENGINE_BYTES = 2L * 1_024 * 1_024

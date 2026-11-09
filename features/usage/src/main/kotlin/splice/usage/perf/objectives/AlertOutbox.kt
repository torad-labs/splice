package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables

/** Dispatch is serialized with evaluation. Attempts are persisted before queue acceptance and never loop without a bound. */
internal class AlertOutbox(
    private val delivery: PerfAlertDelivery,
    private val journal: AlertJournal,
    private val store: PerfObjectiveStore,
) {
    fun add(data: AlertJournalData, event: JsonObject, rule: AlertRule, revision: String): AlertJournalData {
        if (!rule.deliveryEnabled || ObjectiveJson.text(event, "state") != "firing") return data
        val key = requireNotNull(ObjectiveJson.text(event, "dedup_key"))
        if (data.deliveries.any { it.key == key }) return data
        val retained = data.deliveries.filter { it.status !in finished }
        return if (retained.size >= MAX_DELIVERIES) {
            mark(
                data.copy(deliveryRejected = data.deliveryRejected + 1),
                key,
                "queue_full",
            )
        } else {
            val attempt = AlertAttempt(key, rule.id, revision, event)
            data.copy(deliveries = (retained + attempt).takeLast(MAX_DELIVERIES))
        }
    }

    fun advance(now: Long) {
        val keys = journal.data.deliveries.map { it.key }
        for (key in keys) {
            val attempt = journal.data.deliveries.find { it.key == key } ?: continue
            val settings = store.snapshot() ?: return
            if (!advanceAttempt(attempt, settings, now)) return
        }
    }

    private fun advanceAttempt(attempt: AlertAttempt, settings: ObjectiveSettings, now: Long): Boolean {
        val enabled = enabled(attempt, settings)
        return when {
            attempt.status in finished -> true
            attempt.status in setOf("accepted", "dispatching") -> complete(attempt, now)
            !enabled -> save(attempt.copy(status = "disabled"))
            now >= attempt.nextAt -> dispatch(attempt, now)
            else -> true
        }
    }

    private fun enabled(attempt: AlertAttempt, settings: ObjectiveSettings): Boolean {
        val state = journal.data.states[attempt.ruleId]
        return listOf(
            settings.rules.any { it.id == attempt.ruleId && it.deliveryEnabled },
            settings.revision == ObjectiveJson.text(attempt.event, "settings_revision"),
            state?.active == true,
            state?.alertId == ObjectiveJson.text(attempt.event, "alert_id"),
        ).all { it }
    }

    private fun complete(attempt: AlertAttempt, now: Long): Boolean {
        val result = delivery.result(attempt.key)
        if (result == null) {
            return if (now - attempt.nextAt > COMPLETION_WAIT_MS) {
                save(
                    attempt.copy(status = "delivery_result_unknown"),
                )
            } else {
                true
            }
        }
        val status = when {
            result == ALERT_DELIVERY_FAILED && attempt.attempts < MAX_ATTEMPTS -> "retry_pending"
            result in setOf("delivered", ALERT_DELIVERY_FAILED, "disabled") -> result
            else -> "delivery_result_unknown"
        }
        return save(
            attempt.copy(status = status, nextAt = now + retryDelay(attempt)),
            failed = result == ALERT_DELIVERY_FAILED,
        )
    }

    private fun dispatch(attempt: AlertAttempt, now: Long): Boolean {
        if (attempt.attempts >= MAX_ATTEMPTS) return save(attempt.copy(status = "enqueue_exhausted"))
        val prepared = attempt.copy(status = "dispatching", attempts = attempt.attempts + 1)
        if (!save(prepared)) return false
        val accepted = Cancellables.runCatchingCancellable { delivery.enqueue(attempt.event) }.fold(
            onSuccess = { it },
            // A thrown queue operation is a failed acceptance, never an actual HTTP delivery failure.
            onFailure = { false },
        )
        val status = if (accepted) {
            "accepted"
        } else if (prepared.attempts < MAX_ATTEMPTS) {
            "enqueue_retry"
        } else {
            "enqueue_exhausted"
        }
        return save(prepared.copy(status = status, nextAt = now + retryDelay(prepared)), rejected = !accepted)
    }

    private fun save(attempt: AlertAttempt, failed: Boolean = false, rejected: Boolean = false): Boolean {
        val data = journal.data.copy(
            deliveries = journal.data.deliveries.map { if (it.key == attempt.key) attempt else it },
            deliveryFailures = journal.data.deliveryFailures + if (failed) 1 else 0,
            enqueueFailures = journal.data.enqueueFailures + if (rejected) 1 else 0,
        )
        return journal.publish(mark(data, attempt.key, attempt.status))
    }

    private fun mark(data: AlertJournalData, key: String, status: String): AlertJournalData {
        fun update(event: JsonObject): JsonObject = if (ObjectiveJson.text(event, "dedup_key") == key) {
            JsonObject(event + ("delivery_status" to ObjectiveJson.text(status)))
        } else {
            event
        }
        return data.copy(events = data.events.map(::update), latest = data.latest.mapValues { update(it.value) })
    }

    private fun retryDelay(attempt: AlertAttempt): Long {
        // Stable per-event jitter avoids synchronizing configured rules, and remains deterministic across restart.
        val jitter = (attempt.key.hashCode().toLong() and JITTER_MASK) % BASE_RETRY_MS
        return BASE_RETRY_MS * (attempt.attempts + 1) + jitter
    }
}

// Persist at most three dispatch attempts so failed or refused webhook delivery cannot retry indefinitely.
private const val MAX_ATTEMPTS = 3
private const val ALERT_DELIVERY_FAILED = "failed"

// Space retry attempts by a five-second base delay plus deterministic jitter to avoid immediate repeated sends.
private const val BASE_RETRY_MS = 5_000L

// Wait thirty seconds for an accepted delivery result before classifying its outcome as unknown.
private const val COMPLETION_WAIT_MS = 30_000L

// Clear the sign bit of the 32-bit key hash before reducing it to a nonnegative retry jitter.
private const val JITTER_MASK = 0x7fffffffL
private val finished =
    setOf(
        "delivered",
        ALERT_DELIVERY_FAILED,
        "disabled",
        "enqueue_exhausted",
        "restart_delivery_unknown",
        "delivery_result_unknown",
    )

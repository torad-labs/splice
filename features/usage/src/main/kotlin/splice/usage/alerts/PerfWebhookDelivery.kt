package splice.usage.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.objectives.PerfAlertDelivery
import splice.usage.perf.objectives.PerfObjectiveStore

/** One bounded worker reuses AlertDelivery; desktop consumption belongs to the console with preference and permission. */
public class PerfWebhookDelivery(
    private val transport: AlertDelivery,
    scope: CoroutineScope,
    private val objectives: PerfObjectiveStore? = null,
) : PerfAlertDelivery, AutoCloseable {
    private val lock = Any()
    private val lease = PerfWorkBudget.acquire(QUEUE_BYTES)
    private val queue = Channel<JsonObject>(QUEUE_SIZE)
    private val results = LinkedHashMap<String, String>()
    private var accepted = 0L
    private var delivered = 0L
    private var failed = 0L
    private var rejected = 0L
    private var disabled = 0L
    private var pending = 0L
    private var closed = false
    private val worker = scope.launch {
        for (event in queue) {
            val result = if (enabled(event)) {
                withTimeoutOrNull(DELIVERY_TIMEOUT_MS) { transport.performance(event) } ?: "failed"
            } else {
                "disabled"
            }
            synchronized(lock) {
                val key = requireNotNull(JsonScalars.str(event, "dedup_key"))
                results[key] = result
                pending--
                when (result) {
                    "delivered" -> delivered++
                    "failed" -> failed++
                    else -> disabled++
                }
                prune()
            }
        }
    }.also { job -> job.invokeOnCompletion { lease?.close() } }

    override fun enqueue(event: JsonObject): Boolean = synchronized(lock) {
        val key = JsonScalars.str(event, "dedup_key")
        val reduced = reduce(event)
        if (listOf(closed, !worker.isActive, lease == null, key == null, reduced == null).any { it }) {
            rejected++
            return@synchronized false
        }
        if (results[key] in setOf(ACCEPTED, "delivered")) return@synchronized true
        val sent = queue.trySend(requireNotNull(reduced)).isSuccess
        if (sent) {
            results[requireNotNull(key)] = ACCEPTED
            accepted++
            pending++
            prune()
        } else {
            rejected++
        }
        sent
    }

    override fun result(dedupKey: String): String? = synchronized(lock) {
        results[dedupKey]?.takeUnless { it == ACCEPTED }
    }

    override fun status(): JsonObject = synchronized(lock) {
        buildJsonObject {
            put(ACCEPTED, accepted)
            put("delivered", delivered)
            put("failed", failed)
            put("rejected", rejected)
            put("disabled", disabled)
            put("pending", pending)
            put("closed", closed)
            put("completion", "actual_webhook_http_result")
            put("desktop", "browser_preference_and_permission_required")
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            queue.cancel()
            worker.cancel()
        }
    }

    private fun reduce(event: JsonObject): JsonObject? {
        if (JsonScalars.str(
                event,
                "delivery_enabled",
            ) != "true" || JsonScalars.str(event, "state") != "firing"
        ) {
            return null
        }
        if (fields.any { (JsonScalars.str(event, it)?.length ?: MAX_FIELD_CHARS + 1) > MAX_FIELD_CHARS }) return null
        return JsonObject(event.filterKeys { it in fields || it == "settings_revision" })
    }

    private fun enabled(event: JsonObject): Boolean {
        val store = objectives ?: return true
        val current = store.snapshot() ?: return false
        val ruleId = JsonScalars.str(event, "rule_id")
        return current.revision == JsonScalars.str(event, "settings_revision") &&
            current.rules.any { it.id == ruleId && it.deliveryEnabled }
    }

    private fun prune() {
        while (results.size > RESULT_SIZE) {
            val completed = results.entries.firstOrNull { it.value != ACCEPTED } ?: return
            results.remove(completed.key)
        }
    }
}

private const val QUEUE_SIZE = 32
private const val ACCEPTED = "accepted"
private const val DELIVERY_TIMEOUT_MS = 10_000L
private const val RESULT_SIZE = 64
private const val MAX_FIELD_CHARS = 160
private const val QUEUE_BYTES = 256L * 1_024
private val fields = setOf("dedup_key", "rule_id", "metric", "state", "observed", "threshold", "delivery_enabled")

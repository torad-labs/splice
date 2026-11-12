package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonObject

/** Nonblocking bounded queue acceptance. True never implies the destination received the event. */
public fun interface PerfAlertDelivery {
    public fun enqueue(event: JsonObject): Boolean

    /** Actual completed attempt: delivered, failed, disabled; null means accepted but completion unknown. */
    public fun result(dedupKey: String): String? = null

    /** Bounded, content-free transport accounting; no URLs or response bodies. */
    public fun status(): JsonObject = JsonObject(emptyMap())
}

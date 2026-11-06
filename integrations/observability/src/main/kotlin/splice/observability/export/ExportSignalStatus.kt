package splice.observability.export

import io.opentelemetry.sdk.common.CompletableResultCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class ExportSignalStatus {
    val enqueued = AtomicLong()
    val exported = AtomicLong()
    val failed = AtomicLong()
    val dropped = AtomicLong()
    val pending = AtomicLong()
    val lastSuccess = AtomicLong()

    fun finish(result: CompletableResultCode, count: Int, now: Long): Boolean {
        result.join(EXPORT_WAIT_MS, TimeUnit.MILLISECONDS)
        val success = result.isSuccess
        if (success) {
            exported.addAndGet(count.toLong()); lastSuccess.set(now)
        } else { failed.addAndGet(count.toLong()); dropped.addAndGet(count.toLong()) }
        pending.addAndGet(-count.toLong())
        return success
    }

    fun json(): JsonObject = buildJsonObject {
        put("enqueued", enqueued.get())
        put("exported", exported.get())
        put("failed", failed.get())
        put("dropped", dropped.get())
        put("pending", pending.get())
        put("last_success_epoch_ms", lastSuccess.get().takeIf { it > 0 })
    }
}

// Allow the two 750 ms transport attempts plus backoff, then release the worker from SDK completion waits.
internal const val EXPORT_WAIT_MS = 2_500L

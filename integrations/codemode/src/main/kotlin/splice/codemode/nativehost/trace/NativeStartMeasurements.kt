package splice.codemode.nativehost.trace

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey

/** Fixed-size operation preparation readings travel with existing context, never a global ID cache. */
internal class NativeStartMeasurements {
    // A successful execution that reuses its logical session never opens the host connection.
    // The connection opener replaces this zero with measured startup time when it runs.
    private val values = linkedMapOf("tool_cold_start_ms" to 0.0)

    @Synchronized
    fun record(metric: String, value: Double) { values[metric] = value }

    @Synchronized
    fun snapshot(): Map<String, Double> = values.toMap()
}

internal object NativeStartTiming {
    val key: ContextKey<NativeStartMeasurements> = ContextKey.named("splice.perf.start")

    fun record(metric: String, value: Double) { Context.current().get(key)?.record(metric, value) }
}

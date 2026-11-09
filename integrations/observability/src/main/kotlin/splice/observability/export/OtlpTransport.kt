package splice.observability.export

import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.export.RetryPolicy
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import splice.core.perf.export.PerfExportConfig
import java.time.Duration

/** Created only for an enabled explicit endpoint. No autoconfiguration, environment discovery or global SDK. */
internal class OtlpTransport(private val config: PerfExportConfig) {
    val metrics: MetricExporter? = if ("metrics" in config.signals) metrics() else null
    val traces: SpanExporter? = if ("traces" in config.signals) traces() else null
    val logs: LogRecordExporter? = if ("logs" in config.signals) logs() else null

    fun shutdown(): CompletableResultCode = CompletableResultCode.ofAll(
        listOfNotNull(metrics?.shutdown(), traces?.shutdown(), logs?.shutdown()),
    )

    private fun endpoint(signal: String): String = requireNotNull(config.endpoint).trimEnd('/') +
        if (config.protocol == "grpc") "" else "/v1/$signal"

    private fun metrics(): MetricExporter = if (config.protocol == "grpc") {
        OtlpGrpcMetricExporter.builder().setEndpoint(endpoint("metrics")).setTimeout(TIMEOUT)
            .setRetryPolicy(
                RETRY,
            ).setMeterProvider(MeterProvider.noop()).apply { config.headers.forEach(::addHeader) }.build()
    } else {
        OtlpHttpMetricExporter.builder().setEndpoint(endpoint("metrics")).setTimeout(TIMEOUT).setConnectTimeout(TIMEOUT)
            .setRetryPolicy(
                RETRY,
            ).setMeterProvider(MeterProvider.noop()).apply { config.headers.forEach(::addHeader) }.build()
    }

    private fun traces(): SpanExporter = if (config.protocol == "grpc") {
        OtlpGrpcSpanExporter.builder().setEndpoint(endpoint("traces")).setTimeout(TIMEOUT)
            .setRetryPolicy(
                RETRY,
            ).setMeterProvider(MeterProvider.noop()).apply { config.headers.forEach(::addHeader) }.build()
    } else {
        OtlpHttpSpanExporter.builder().setEndpoint(endpoint("traces")).setTimeout(TIMEOUT).setConnectTimeout(TIMEOUT)
            .setRetryPolicy(
                RETRY,
            ).setMeterProvider(MeterProvider.noop()).apply { config.headers.forEach(::addHeader) }.build()
    }

    private fun logs(): LogRecordExporter = if (config.protocol == "grpc") {
        OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint("logs")).setTimeout(TIMEOUT)
            .setRetryPolicy(
                RETRY,
            ).setMeterProvider(MeterProvider.noop()).apply { config.headers.forEach(::addHeader) }.build()
    } else {
        OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint("logs")).setTimeout(TIMEOUT).setConnectTimeout(TIMEOUT)
            .setRetryPolicy(
                RETRY,
            ).setMeterProvider(MeterProvider.noop()).apply { config.headers.forEach(::addHeader) }.build()
    }
}

// Limit each optional collector attempt to 750 ms so an unavailable endpoint cannot hold the export lane indefinitely.
private const val ATTEMPT_MS = 750L

// Wait 100 ms before the single retry instead of immediately repeating a failed collector request.
private const val INITIAL_BACKOFF_MS = 100L

// Cap retry delay at 200 ms to keep transport retry time within the worker's bounded completion wait.
private const val OTLP_MAX_BACKOFF_MS = 200L

// Permit one retry after the first attempt, bounding collector failure amplification to two requests.
private const val OTLP_MAX_ATTEMPTS = 2

// Use exponential doubling for the SDK retry policy, bounded by the explicit maximum delay and attempt count.
private const val BACKOFF_MULTIPLIER = 2.0
private val TIMEOUT = Duration.ofMillis(ATTEMPT_MS)
private val RETRY = RetryPolicy.builder().setMaxAttempts(OTLP_MAX_ATTEMPTS)
    .setInitialBackoff(Duration.ofMillis(INITIAL_BACKOFF_MS)).setMaxBackoff(Duration.ofMillis(OTLP_MAX_BACKOFF_MS))
    .setBackoffMultiplier(BACKOFF_MULTIPLIER).build()

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

private const val ATTEMPT_MS = 750L
private const val INITIAL_BACKOFF_MS = 100L
private const val MAX_BACKOFF_MS = 200L
private const val MAX_ATTEMPTS = 2
private const val BACKOFF_MULTIPLIER = 2.0
private val TIMEOUT = Duration.ofMillis(ATTEMPT_MS)
private val RETRY = RetryPolicy.builder().setMaxAttempts(MAX_ATTEMPTS)
    .setInitialBackoff(Duration.ofMillis(INITIAL_BACKOFF_MS)).setMaxBackoff(Duration.ofMillis(MAX_BACKOFF_MS))
    .setBackoffMultiplier(BACKOFF_MULTIPLIER).build()

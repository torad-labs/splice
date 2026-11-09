package splice.app.observability

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import splice.usage.alerts.AlertDelivery
import splice.usage.alerts.AlertStore
import splice.usage.alerts.PerfWebhookDelivery
import splice.usage.perf.objectives.PerfAlertEngine
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQueryService

/** Owns alert evaluation and delivery; stop delivery before shared-scope cancellation, then close its HTTP transport. */
internal class PerfAlerting(
    query: PerfQueryService,
    objectives: PerfObjectiveStore,
    alerts: AlertStore,
    log: LogSink,
    scope: CoroutineScope,
) : AutoCloseable {
    private val deliveryClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = ALERT_HTTP_TIMEOUT_MS
            requestTimeoutMillis = ALERT_HTTP_TIMEOUT_MS
            socketTimeoutMillis = ALERT_HTTP_TIMEOUT_MS
        }
    }
    private val delivery = PerfWebhookDelivery(AlertDelivery(alerts, log, scope, deliveryClient), scope, objectives)
    val engine = PerfAlertEngine(query, objectives, delivery)

    fun evaluate(nowMs: Long) { PerfWorkBudget.release(engine.evaluate(nowMs)) }

    fun stopDelivery(cleanup: PerfCleanup) = cleanup.finish(
        { cleanup.run("alerts") { engine.close() } },
        { cleanup.run("delivery") { delivery.close() } },
    )

    /** The app calls this after stopDelivery and cancellation of the shared delivery/telemetry scope. */
    override fun close() { deliveryClient.close() }
}

// Bound webhook connect, request and socket waits to ten seconds on the alert delivery lane.
private const val ALERT_HTTP_TIMEOUT_MS = 10_000L

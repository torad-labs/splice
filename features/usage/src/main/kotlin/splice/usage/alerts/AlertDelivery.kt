// NEW: V4-133 review — a reached `warn` budget, delivered through the alert settings GET/PUT
// /api/alerts saves.
//
// THE WEBHOOK gets the same `{"text": ...}` body the route's test send posts (AlertRoutes.test), so an
// endpoint that passed the operator's test receives the real alert in the shape it already accepted.
// The saved URL is read per alert, so a webhook saved after boot is the one used.
//
// OFF THE TURN PATH: the POST is launched on the owner's scope and bounded by timeouts, because the
// budget calls this from a turn's admission. A failed or refused delivery is logged under the head,
// never retried: the head's own log carries the same warning line whatever the webhook does.
//
// DESKTOP IS NOT DELIVERED HERE, and not anywhere yet. AlertRoutes.kt's header already rules the daemon
// out ("DESKTOP IS THE CONSOLE'S OWN JOB": a headless process has no desktop to notify), and the
// console that would own it shows no notification and has no event to show one from: the /api/events
// kinds were decided on 2026-09-18 (.dev/campaigns/web-console/FEATURES.md §6) and carry no budget
// family. `desktop: true` therefore reaches the operator through nothing but the head's log line.
package splice.usage.alerts

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.usage.budgets.BudgetAlert

// why: an alert waits for one network round trip to an operator-chosen endpoint, never longer —
// a dead endpoint must not pile up coroutines behind a day's worth of warnings.
private const val DELIVERY_TIMEOUT_MS = 10_000L

/** The webhook body, the same shape as the route's test send. */
@Serializable
private data class WebhookText(val text: String)

public class AlertDelivery(
    private val source: AlertStore,
    private val log: LogSink,
    /** The owner's scope (ControlPlane's probe scope), cancelled when the daemon stops. */
    private val scope: CoroutineScope,
    private val client: HttpClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = DELIVERY_TIMEOUT_MS
            requestTimeoutMillis = DELIVERY_TIMEOUT_MS
            socketTimeoutMillis = DELIVERY_TIMEOUT_MS
        }
    },
) : BudgetAlert {
    private val json = Json { encodeDefaults = true }

    override fun budgetReached(head: String, text: String) {
        val url = source.settings().webhookUrl ?: return
        scope.launch { post(head, url, text) }
    }

    private suspend fun post(head: String, url: String, text: String) {
        Cancellables.runCatchingCancellable {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(WebhookText.serializer(), WebhookText(text)))
            }
        }.fold(
            onSuccess = { response ->
                if (!response.status.isSuccess()) {
                    val status = response.status.value.toString()
                    log("[${LogSafe.str(head)}][budget] webhook alert answered HTTP ${LogSafe.str(status)}\n")
                }
            },
            onFailure = { failure ->
                log("[${LogSafe.str(head)}][budget] webhook alert failed: ${LogSafe.str(failure.toString())}\n")
            },
        )
    }
}

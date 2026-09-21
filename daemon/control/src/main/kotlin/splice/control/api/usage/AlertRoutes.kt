// NEW: V4-133, FEATURES.md §5/§6 — GET/PUT /api/alerts, plus the "test send" §6 names beside them.
//
//   GET  /api/alerts        {desktop: bool, webhook_url: string|null}
//   PUT  /api/alerts        body the same shape -> the settings as SAVED
//   POST /api/alerts/test   fires ONE test delivery through whatever is configured RIGHT NOW
//                            (never the request body — a test send tests what an operator already
//                            saved, so PUT then test is the honest order, and a route that tested an
//                            unsaved draft could pass while the saved setting is broken)
//
// webui/src/entities/alert/api/index.ts already calls POST /api/alerts/test rather than a verb on
// the GET/PUT pair, reading FEATURES.md §6's "test send" as its own operation — this route answers
// exactly that path.
//
// DESKTOP IS THE CONSOLE'S OWN JOB. This route's "desktop" leg fires nothing from the daemon (a
// headless process has no desktop to notify); it exists so the setting round-trips and the console
// UI decides whether to show a browser Notification from here. The WEBHOOK leg is the one this
// route can actually test: a real POST to the saved URL.
package splice.control.api.usage

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.api.sessions.JsonReply
import splice.core.alert.AlertSettings
import splice.core.alert.AlertStore
import splice.core.util.Cancellables

/** The daemon's alert-settings store, read per request — the same discipline [BudgetSource] and
 *  [TeamSource] keep. */
internal fun interface AlertSource {
    public operator fun invoke(): AlertStore?
}

internal const val ALERTS_UNWIRED = "the daemon wired no alert store; /api/alerts cannot report it"

// why: a test webhook waits for a real network round trip, not the daemon's own request budget —
// bounded so a slow or dead endpoint cannot hold the route open indefinitely.
private const val TEST_TIMEOUT_MS = 10_000L

private const val BAD_ALERT_BODY = "the body must be {\"desktop\": bool, \"webhook_url\": string|null}"

@Serializable
private data class TestPing(val text: String = "splice test alert — GET/PUT /api/alerts is wired")

internal class AlertRoutes(
    private val source: AlertSource,
    private val client: HttpClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = TEST_TIMEOUT_MS
            requestTimeoutMillis = TEST_TIMEOUT_MS
            socketTimeoutMillis = TEST_TIMEOUT_MS
        }
    },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun read(): JsonReply = withStore { store -> JsonReply(HttpStatusCode.OK, settingsJson(store.settings())) }

    public fun write(body: String): JsonReply = withStore { store ->
        // ast-grep-ignore: kt-no-silent-result-collapse -- a body that is not JSON and a body of the wrong shape get the same answer, one 400 naming the shape expected, so the failure has nothing more to say
        val parsed = Cancellables.runCatchingCancellable { json.decodeFromString(AlertSettings.serializer(), body) }
            .getOrNull() ?: return@withStore refuse(HttpStatusCode.BadRequest, BAD_ALERT_BODY)
        Cancellables.runCatchingCancellable { store.replace(parsed) }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, settingsJson(it)) },
            onFailure = { failure ->
                refuse(HttpStatusCode.BadRequest, failure.message ?: "the alert write failed with no reason given")
            },
        )
    }

    private fun settingsJson(settings: AlertSettings): String =
        json.encodeToString(AlertSettings.serializer(), settings)

    /** POST /api/alerts/test: sends [TestPing] to the SAVED webhook. No webhook saved is a 409
     *  naming why — never a silent 200 for a test that tested nothing. */
    public suspend fun test(): JsonReply {
        val store = source() ?: return refuse(HttpStatusCode.ServiceUnavailable, ALERTS_UNWIRED)
        val url = store.settings().webhookUrl
            ?: return refuse(HttpStatusCode.Conflict, "no webhook_url saved; PUT /api/alerts first")
        val sent = Cancellables.runCatchingCancellable {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(TestPing.serializer(), TestPing()))
            }
        }
        return sent.fold(
            onSuccess = { response ->
                JsonReply(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put("ok", true)
                        put("status", response.status.value)
                    }.toString(),
                )
            },
            onFailure = { failure ->
                val why = failure.message ?: failure::class.simpleName
                refuse(HttpStatusCode.BadGateway, "webhook test failed: $why")
            },
        )
    }

    private inline fun withStore(block: (AlertStore) -> JsonReply): JsonReply {
        val store = source() ?: return refuse(HttpStatusCode.ServiceUnavailable, ALERTS_UNWIRED)
        return block(store)
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
